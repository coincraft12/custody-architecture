package lab.custody.orchestration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lab.custody.adapter.BroadcastRejectedException;
import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.domain.ledger.LedgerEntry;
import lab.custody.domain.outbox.OutboxEvent;
import lab.custody.domain.outbox.OutboxEventRepository;
import lab.custody.domain.txattempt.AttemptExceptionType;
import lab.custody.domain.nonce.NonceReservation;
import lab.custody.domain.policy.PolicyAuditLog;
import lab.custody.domain.policy.PolicyAuditLogRepository;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lab.custody.orchestration.policy.PolicyDecision;
import lab.custody.orchestration.policy.PolicyEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class WithdrawalService {

    private final WithdrawalRepository withdrawalRepository;
    private final AttemptService attemptService;
    private final PolicyEngine policyEngine;
    private final PolicyAuditLogRepository policyAuditLogRepository;
    private final ChainAdapterRouter router;
    private final NonceAllocator nonceAllocator;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, ReentrantLock> idempotencyLocks = new ConcurrentHashMap<>();

    private final Counter createdCounter;
    private final Counter broadcastedCounter;
    private final Timer createDurationTimer;

    private final OutboxEventRepository outboxEventRepository;
    private final MeterRegistry meterRegistry;

    public WithdrawalService(
            WithdrawalRepository withdrawalRepository,
            AttemptService attemptService,
            PolicyEngine policyEngine,
            PolicyAuditLogRepository policyAuditLogRepository,
            ChainAdapterRouter router,
            NonceAllocator nonceAllocator,
            TransactionTemplate transactionTemplate,
            OutboxEventRepository outboxEventRepository,
            MeterRegistry meterRegistry
    ) {
        this.withdrawalRepository = withdrawalRepository;
        this.attemptService = attemptService;
        this.policyEngine = policyEngine;
        this.policyAuditLogRepository = policyAuditLogRepository;
        this.router = router;
        this.nonceAllocator = nonceAllocator;
        this.transactionTemplate = transactionTemplate;
        this.outboxEventRepository = outboxEventRepository;
        this.meterRegistry = meterRegistry;

        this.createdCounter = Counter.builder("custody.withdrawal.created.total")
                .description("Total number of new withdrawals created")
                .register(meterRegistry);
        this.broadcastedCounter = Counter.builder("custody.withdrawal.broadcasted.total")
                .description("Total number of withdrawals successfully broadcasted")
                .register(meterRegistry);
        this.createDurationTimer = Timer.builder("custody.withdrawal.create.duration")
                .description("Time to create and broadcast a withdrawal")
                .register(meterRegistry);
    }

    @Autowired(required = false)
    private ApprovalService approvalService;

    @Autowired(required = false)
    private LedgerService ledgerService;

    @Autowired(required = false)
    private ConfirmationTracker confirmationTracker;

    @Value("${custody.confirmation-tracker.auto-start:true}")
    private boolean confirmationTrackerAutoStart;

    public Withdrawal createOrGet(String idempotencyKey, CreateWithdrawalRequest req) {
        ChainType chainType = parseChainType(req.chainType());
        log.info(
                "event=withdrawal_service.create_or_get.start idempotencyKey={} chainType={} asset={} amount={} toAddress={}",
                idempotencyKey,
                chainType,
                req.asset(),
                req.amount(),
                req.toAddress()
        );
        ReentrantLock lock = idempotencyLocks.computeIfAbsent(idempotencyKey, key -> new ReentrantLock());
        lock.lock();
        try {
            Withdrawal result = transactionTemplate.execute(status ->
                    withdrawalRepository.findByIdempotencyKey(idempotencyKey)
                            .map(existing -> validateIdempotentRequest(existing, chainType, req))
                            .orElseGet(() -> createAndBroadcast(idempotencyKey, chainType, req))
            );
            if (result == null) {
                throw new IllegalStateException("failed to create or get withdrawal");
            }
            log.info(
                    "event=withdrawal_service.create_or_get.done idempotencyKey={} withdrawalId={} status={}",
                    idempotencyKey,
                    result.getId(),
                    result.getStatus()
            );
            return result;
        } finally {
            lock.unlock();
        }
    }

    private Withdrawal createAndBroadcast(String idempotencyKey, ChainType chainType, CreateWithdrawalRequest req) {
        return createDurationTimer.record(() -> doCreateAndBroadcast(idempotencyKey, chainType, req));
    }

    private Withdrawal doCreateAndBroadcast(String idempotencyKey, ChainType chainType, CreateWithdrawalRequest req) {
        long amountWei = ethToWei(req.amount());

        Withdrawal saved = withdrawalRepository.save(Withdrawal.requested(
                idempotencyKey,
                chainType,
                req.fromAddress(),
                req.toAddress(),
                req.asset(),
                amountWei
        ));
        log.info("event=withdrawal_service.create.persisted withdrawalId={} status={}", saved.getId(), saved.getStatus());
        createdCounter.increment();
        if (ledgerService != null) {
            saved = ledgerService.saveWithdrawal(saved);
        }

        PolicyDecision decision = policyEngine.evaluate(req);
        policyAuditLogRepository.save(PolicyAuditLog.of(saved.getId(), decision.allowed(), decision.reason()));
        log.info(
                "event=withdrawal_service.policy.evaluated withdrawalId={} allowed={} reason={}",
                saved.getId(),
                decision.allowed(),
                decision.reason()
        );

        if (!decision.allowed()) {
            saved.transitionTo(WithdrawalStatus.W0_POLICY_REJECTED);
            log.info("event=withdrawal_service.policy.rejected withdrawalId={} status={}", saved.getId(), saved.getStatus());
            Counter.builder("custody.withdrawal.policy_rejected.total")
                    .description("Total number of withdrawals rejected by policy")
                    .tag("reason", decision.reason() != null ? decision.reason() : "unknown")
                    .register(meterRegistry)
                    .increment();
            if (ledgerService != null) {
                return ledgerService.saveWithdrawal(saved);
            }
            return withdrawalRepository.save(saved);
        }

        boolean approved = approvalService == null || approvalService.requestApproval(saved);
        if (!approved) {
            saved.transitionTo(WithdrawalStatus.W0_POLICY_REJECTED);
            log.info("event=withdrawal_service.approval.rejected withdrawalId={} status={}", saved.getId(), saved.getStatus());
            if (ledgerService != null) {
                return ledgerService.saveWithdrawal(saved);
            }
            return withdrawalRepository.save(saved);
        }

        saved.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED);
        saved = withdrawalRepository.save(saved);
        log.info("event=withdrawal_service.state.W1 withdrawalId={}", saved.getId());

        saved.transitionTo(WithdrawalStatus.W3_APPROVED);
        if (ledgerService != null) {
            ledgerService.reserve(saved);
        }
        saved = withdrawalRepository.save(saved);
        log.info("event=withdrawal_service.state.W3 withdrawalId={}", saved.getId());

        saved.transitionTo(WithdrawalStatus.W4_SIGNING);
        saved = withdrawalRepository.save(saved);
        log.info("event=withdrawal_service.state.W4 withdrawalId={}", saved.getId());

        log.info("event=withdrawal_service.approval.approved withdrawalId={}", saved.getId());

        NonceReservation reservation = nonceAllocator.reserve(chainType, req.fromAddress(), saved.getId());
        TxAttempt attempt = null;
        try {
            attempt = attemptService.createAttempt(saved.getId(), req.fromAddress(), reservation.getNonce());
            log.info(
                    "event=withdrawal_service.attempt.created withdrawalId={} attemptId={} attemptNo={} nonce={} reservationId={}",
                    saved.getId(),
                    attempt.getId(),
                    attempt.getAttemptNo(),
                    attempt.getNonce(),
                    reservation.getId()
            );
            broadcastAttempt(saved, attempt);
            nonceAllocator.commit(reservation.getId(), attempt.getId());
        } catch (BroadcastRejectedException e) {
            nonceAllocator.release(reservation.getId());
            if (e.isNonceTooLow() && attempt != null) {
                // 1-4-1/1-4-2: RPC가 "nonce too low"를 반환했으므로 해당 예약을 해제하고
                // 체인의 최신 pending nonce로 재예약 후 1회 재시도한다.
                log.warn("event=withdrawal_service.nonce_too_low.detected withdrawalId={} failedNonce={}",
                        saved.getId(), reservation.getNonce());
                attempt.markException(AttemptExceptionType.RPC_INCONSISTENT, "nonce too low — auto re-reserving");
                attempt.setCanonical(false);
                NonceReservation retryReservation = nonceAllocator.reserve(chainType, req.fromAddress(), saved.getId());
                try {
                    attempt = attemptService.createAttempt(saved.getId(), req.fromAddress(), retryReservation.getNonce());
                    broadcastAttempt(saved, attempt);
                    nonceAllocator.commit(retryReservation.getId(), attempt.getId());
                    log.info("event=withdrawal_service.nonce_too_low.recovered withdrawalId={} newNonce={}",
                            saved.getId(), retryReservation.getNonce());
                } catch (RuntimeException retryEx) {
                    nonceAllocator.release(retryReservation.getId());
                    throw retryEx;
                }
            } else {
                throw e;
            }
        } catch (RuntimeException e) {
            nonceAllocator.release(reservation.getId());
            throw e;
        }

        if (ledgerService != null) {
            ledgerService.saveAttempt(attempt);
            saved = ledgerService.saveWithdrawal(saved);
        } else {
            withdrawalRepository.save(saved);
        }

        if (confirmationTracker != null && confirmationTrackerAutoStart) {
            log.info("event=withdrawal_service.confirmation_tracking.start withdrawalId={} attemptId={}", saved.getId(), attempt.getId());
            confirmationTracker.startTracking(attempt);
        } else if (confirmationTracker != null) {
            log.info(
                    "event=withdrawal_service.confirmation_tracking.skipped withdrawalId={} attemptId={} reason=auto_start_disabled",
                    saved.getId(),
                    attempt.getId()
            );
        }

        return saved;
    }

    private void broadcastAttempt(Withdrawal withdrawal, TxAttempt attempt) {
        // 6-2-1: 브로드캐스트 성공 후 트랜잭션 커밋 전에 DB 저장 실패 시나리오.
        // RPC broadcast()가 반환된 시점에 TX는 이미 mempool에 존재한다.
        // 이후 nonceAllocator.commit() 또는 ledgerService.saveWithdrawal()이 예외를 던지면
        // transactionTemplate이 전체 트랜잭션을 롤백하므로 DB에는 W6_BROADCASTED 기록이 없다.
        // 결과: TX는 mempool에 있지만 DB는 이전 상태를 유지 → 데이터 불일치.
        // 경감 전략: (a) OutboxEvent 기록 (아래), (b) StartupRecoveryService 재추적,
        //            (c) Phase 3 — 트랜잭션 경계를 broadcast 전/후로 분리하여 근본 해결.
        ChainAdapter.BroadcastResult result = router.resolve(withdrawal.getChainType()).broadcast(
                new ChainAdapter.BroadcastCommand(
                        withdrawal.getId(),
                        withdrawal.getFromAddress(),
                        withdrawal.getToAddress(),
                        withdrawal.getAsset(),
                        withdrawal.getAmount(),
                        attempt.getNonce(),
                        attempt.getMaxPriorityFeePerGas(),
                        attempt.getMaxFeePerGas()
                )
        );

        attempt.setTxHash(result.txHash());
        attempt.transitionTo(TxAttemptStatus.BROADCASTED);
        withdrawal.transitionTo(WithdrawalStatus.W5_SIGNED);
        withdrawal.transitionTo(WithdrawalStatus.W6_BROADCASTED);
        broadcastedCounter.increment();
        log.info(
                "event=withdrawal_service.broadcast.success withdrawalId={} attemptId={} txHash={} withdrawalStatus={} attemptStatus={}",
                withdrawal.getId(),
                attempt.getId(),
                result.txHash(),
                withdrawal.getStatus(),
                attempt.getStatus()
        );

        // 6-2-2 / 6-3-3: 같은 트랜잭션 내에 WITHDRAWAL_BROADCASTED Outbox 이벤트 기록.
        // 트랜잭션이 정상 커밋되면 OutboxPublisher가 외부 시스템에 발행한다.
        // ⚠️ 제한(6-2-1 참조): 이 저장도 외부 TX 롤백 시 함께 롤백된다.
        //    Phase 3에서 broadcast 전/후 트랜잭션 분리로 근본 해결 예정.
        String payload = String.format(
                "{\"withdrawalId\":\"%s\",\"txHash\":\"%s\",\"nonce\":%d,\"fromAddress\":\"%s\",\"toAddress\":\"%s\"}",
                withdrawal.getId(), result.txHash(), attempt.getNonce(),
                withdrawal.getFromAddress(), withdrawal.getToAddress());
        outboxEventRepository.save(
                OutboxEvent.create("Withdrawal", withdrawal.getId(), "WITHDRAWAL_BROADCASTED", payload));
    }

    private Withdrawal validateIdempotentRequest(Withdrawal existing, ChainType chainType, CreateWithdrawalRequest req) {
        long reqWei = ethToWei(req.amount());

        boolean matches = existing.getChainType() == chainType
                && existing.getFromAddress().equals(req.fromAddress())
                && existing.getToAddress().equals(req.toAddress())
                && existing.getAsset().equals(req.asset())
                && existing.getAmount() == reqWei;

        if (!matches) {
            log.warn(
                    "event=withdrawal_service.idempotency.conflict existingWithdrawalId={} idempotencyKey={}",
                    existing.getId(),
                    existing.getIdempotencyKey()
            );
            throw new IdempotencyConflictException("same Idempotency-Key cannot be used with a different request body");
        }

        log.info("event=withdrawal_service.idempotency.hit withdrawalId={} idempotencyKey={}", existing.getId(), existing.getIdempotencyKey());
        return existing;
    }

    @Transactional(readOnly = true)
    public Withdrawal get(UUID id) {
        return withdrawalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<PolicyAuditLog> getPolicyAudits(UUID withdrawalId) {
        return policyAuditLogRepository.findByWithdrawalIdOrderByCreatedAtAsc(withdrawalId);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> getLedgerEntries(UUID withdrawalId) {
        if (ledgerService == null) {
            return List.of();
        }
        return ledgerService.getLedgerEntries(withdrawalId);
    }

    private ChainType parseChainType(String chainType) {
        if (chainType == null || chainType.isBlank()) {
            return ChainType.EVM;
        }

        try {
            return ChainType.valueOf(chainType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("invalid chainType: " + chainType);
        }
    }

    private long ethToWei(BigDecimal eth) {
        if (eth == null) {
            throw new InvalidRequestException("amount is required");
        }
        try {
            BigDecimal multiplier = new BigDecimal("1000000000000000000");
            BigInteger wei = eth.multiply(multiplier).toBigIntegerExact();
            return wei.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new InvalidRequestException("invalid amount: must be a decimal with up to 18 fractional digits representing ETH");
        }
    }
}
