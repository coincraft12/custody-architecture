package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.domain.ledger.LedgerEntry;
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
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
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
        TxAttempt attempt;
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
        log.info(
                "event=withdrawal_service.broadcast.success withdrawalId={} attemptId={} txHash={} withdrawalStatus={} attemptStatus={}",
                withdrawal.getId(),
                attempt.getId(),
                result.txHash(),
                withdrawal.getStatus(),
                attempt.getStatus()
        );
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
