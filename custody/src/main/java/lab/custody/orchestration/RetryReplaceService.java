package lab.custody.orchestration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lab.custody.adapter.BroadcastRejectedException;
import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmMockAdapter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.nonce.NonceReservation;
import lab.custody.domain.txattempt.AttemptExceptionType;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lab.custody.sim.fakechain.FakeChain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class RetryReplaceService {

    private static final long DEFAULT_PRIORITY_FEE = 2_000_000_000L;
    private static final long DEFAULT_MAX_FEE = 20_000_000_000L;

    private final WithdrawalRepository withdrawalRepository;
    private final TxAttemptRepository txAttemptRepository;
    private final AttemptService attemptService;
    private final ChainAdapterRouter router;
    private final FakeChain fakeChain;
    private final NonceAllocator nonceAllocator;
    private final Counter retryCounter;
    private final Counter replaceCounter;

    @Autowired(required = false)
    private LedgerService ledgerService;

    public RetryReplaceService(
            WithdrawalRepository withdrawalRepository,
            TxAttemptRepository txAttemptRepository,
            AttemptService attemptService,
            ChainAdapterRouter router,
            FakeChain fakeChain,
            NonceAllocator nonceAllocator,
            MeterRegistry meterRegistry
    ) {
        this.withdrawalRepository = withdrawalRepository;
        this.txAttemptRepository = txAttemptRepository;
        this.attemptService = attemptService;
        this.router = router;
        this.fakeChain = fakeChain;
        this.nonceAllocator = nonceAllocator;
        this.retryCounter = Counter.builder("custody.withdrawal.retry.total")
                .description("Total number of withdrawal retry attempts")
                .register(meterRegistry);
        this.replaceCounter = Counter.builder("custody.withdrawal.replace.total")
                .description("Total number of withdrawal fee-bump replace attempts")
                .register(meterRegistry);
    }

    @Transactional
    public TxAttempt retry(UUID withdrawalId) {
        log.info("event=retry_replace.retry.start withdrawalId={}", withdrawalId);
        Withdrawal w = loadWithdrawal(withdrawalId);
        TxAttempt canonical = loadCanonical(withdrawalId);
        ensureWithinAttemptLimit(withdrawalId);
        canonical.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
        canonical.setCanonical(false);

        NonceReservation reservation = nonceAllocator.reserve(w.getChainType(), canonical.getFromAddress(), withdrawalId);
        TxAttempt retried = null;
        try {
            retried = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), reservation.getNonce());
            broadcast(w, retried);
            nonceAllocator.commit(reservation.getId(), retried.getId());
        } catch (BroadcastRejectedException e) {
            nonceAllocator.release(reservation.getId());
            if (e.isNonceTooLow() && retried != null) {
                log.warn("event=retry_replace.retry.nonce_too_low.detected withdrawalId={} failedNonce={}",
                        withdrawalId, reservation.getNonce());
                retried.markException(AttemptExceptionType.RPC_INCONSISTENT, "nonce too low — auto re-reserving");
                retried.setCanonical(false);
                NonceReservation retryReservation = nonceAllocator.reserve(w.getChainType(), canonical.getFromAddress(), withdrawalId);
                try {
                    retried = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), retryReservation.getNonce());
                    broadcast(w, retried);
                    nonceAllocator.commit(retryReservation.getId(), retried.getId());
                    log.info("event=retry_replace.retry.nonce_too_low.recovered withdrawalId={} newNonce={}",
                            withdrawalId, retryReservation.getNonce());
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

        nonceAllocator.releaseByAttemptIdIfPresent(canonical.getId());

        TxAttempt saved = txAttemptRepository.save(retried);
        retryCounter.increment();
        log.info(
                "event=retry_replace.retry.done withdrawalId={} attemptId={} nonce={} status={} canonical={} reservationId={}",
                withdrawalId,
                saved.getId(),
                saved.getNonce(),
                saved.getStatus(),
                saved.isCanonical(),
                reservation.getId()
        );
        return saved;
    }

    @Transactional
    public TxAttempt replace(UUID withdrawalId) {
        log.info("event=retry_replace.replace.start withdrawalId={}", withdrawalId);
        Withdrawal w = loadWithdrawal(withdrawalId);
        TxAttempt canonical = loadCanonical(withdrawalId);

        if (canonical.getStatus() == TxAttemptStatus.INCLUDED
                || canonical.getStatus() == TxAttemptStatus.SUCCESS
                || canonical.getStatus() == TxAttemptStatus.FAILED) {
            throw new InvalidRequestException("Cannot replace attempt after it is already finalized on-chain. "
                    + "Create a new withdrawal/retry instead.");
        }

        if (isNonceAlreadyIncluded(w, canonical)) {
            throw new InvalidRequestException("Nonce already included on-chain for current canonical attempt (would fail with nonce too low). "
                    + "replace cannot be executed; create a new retry with a fresh nonce.");
        }

        canonical.transitionTo(TxAttemptStatus.REPLACED);
        canonical.markException(AttemptExceptionType.REPLACED, "fee bump replacement");
        canonical.setCanonical(false);
        txAttemptRepository.save(canonical);

        TxAttempt replaced = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), canonical.getNonce());
        replaced.setFeeParams(
                bumpedFee(canonical.getMaxPriorityFeePerGas(), DEFAULT_PRIORITY_FEE),
                bumpedFee(canonical.getMaxFeePerGas(), DEFAULT_MAX_FEE)
        );
        broadcast(w, replaced);
        nonceAllocator.rebindAttemptIfPresent(canonical.getId(), replaced.getId());

        TxAttempt saved = txAttemptRepository.save(replaced);
        replaceCounter.increment();
        log.info(
                "event=retry_replace.replace.done withdrawalId={} attemptId={} nonce={} status={} canonical={} maxPriorityFeePerGas={} maxFeePerGas={}",
                withdrawalId,
                saved.getId(),
                saved.getNonce(),
                saved.getStatus(),
                saved.isCanonical(),
                saved.getMaxPriorityFeePerGas(),
                saved.getMaxFeePerGas()
        );
        return saved;
    }

    @Transactional
    public TxAttempt sync(UUID withdrawalId, long timeoutMs, long pollMs) {
        log.info("event=retry_replace.sync.start withdrawalId={} timeoutMs={} pollMs={}", withdrawalId, timeoutMs, pollMs);
        Withdrawal w = loadWithdrawal(withdrawalId);
        TxAttempt canonical = loadCanonical(withdrawalId);
        ChainAdapter adapter = router.resolve(w.getChainType());
        if (adapter instanceof EvmMockAdapter) {
            return simulateConfirmation(withdrawalId);
        }
        if (adapter instanceof EvmRpcAdapter rpcAdapter && canonical.getTxHash() != null) {
            long normalizedTimeoutMs = Math.max(timeoutMs, 0L);
            long normalizedPollMs = Math.max(pollMs, 100L);
            long start = System.currentTimeMillis();

            while (System.currentTimeMillis() - start <= normalizedTimeoutMs) {
                var receiptOpt = rpcAdapter.getReceipt(canonical.getTxHash());
                if (receiptOpt.isPresent()) {
                    log.info("event=retry_replace.sync.receipt_found withdrawalId={} attemptId={} txHash={}", withdrawalId, canonical.getId(), canonical.getTxHash());
                    canonical.transitionTo(TxAttemptStatus.INCLUDED);
                    if ("0x1".equalsIgnoreCase(receiptOpt.get().getStatus())) {
                        canonical.transitionTo(TxAttemptStatus.SUCCESS);
                        w.transitionTo(WithdrawalStatus.W7_INCLUDED);
                    } else {
                        canonical.transitionTo(TxAttemptStatus.FAILED);
                    }
                    break;
                }

                if (normalizedTimeoutMs == 0L) {
                    break;
                }

                try {
                    Thread.sleep(normalizedPollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("event=retry_replace.sync.interrupted withdrawalId={} attemptId={}", withdrawalId, canonical.getId());
                    throw new IllegalStateException("Interrupted while waiting for transaction receipt", e);
                }
            }
        }
        TxAttempt saved = txAttemptRepository.save(canonical);
        log.info(
                "event=retry_replace.sync.done withdrawalId={} attemptId={} status={} canonical={}",
                withdrawalId,
                saved.getId(),
                saved.getStatus(),
                saved.isCanonical()
        );
        return saved;
    }

    @Transactional
    public TxAttempt simulateBroadcast(UUID withdrawalId) {
        log.info("event=retry_replace.simulate_broadcast.start withdrawalId={}", withdrawalId);
        Withdrawal withdrawal = loadWithdrawal(withdrawalId);
        TxAttempt canonical = loadCanonical(withdrawalId);
        FakeChain.NextOutcome outcome = fakeChain.consumeOutcome(withdrawalId);

        if (outcome == FakeChain.NextOutcome.FAIL_SYSTEM) {
            canonical.markException(AttemptExceptionType.FAILED_SYSTEM, "simulated failure");
            canonical.setCanonical(false);
            txAttemptRepository.save(canonical);

            TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), canonical.getNonce());
            TxAttempt saved = txAttemptRepository.save(newAttempt);
            log.info("event=retry_replace.simulate_broadcast.fail_system withdrawalId={} newAttemptId={}", withdrawalId, saved.getId());
            return saved;
        }

        if (outcome == FakeChain.NextOutcome.REPLACED) {
            canonical.markException(AttemptExceptionType.REPLACED, "simulated replace");
            canonical.transitionTo(TxAttemptStatus.REPLACED);
            canonical.setCanonical(false);
            txAttemptRepository.save(canonical);

            TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), canonical.getNonce());
            TxAttempt saved = txAttemptRepository.save(newAttempt);
            log.info("event=retry_replace.simulate_broadcast.replaced withdrawalId={} newAttemptId={}", withdrawalId, saved.getId());
            return saved;
        }

        canonical.transitionTo(TxAttemptStatus.INCLUDED);
        withdrawal.transitionTo(WithdrawalStatus.W7_INCLUDED);
        withdrawalRepository.save(withdrawal);
        TxAttempt saved = txAttemptRepository.save(canonical);
        log.info("event=retry_replace.simulate_broadcast.included withdrawalId={} attemptId={}", withdrawalId, saved.getId());
        return saved;
    }

    @Transactional
    public TxAttempt simulateConfirmation(UUID withdrawalId) {
        log.info("event=retry_replace.simulate_confirmation.start withdrawalId={}", withdrawalId);
        Withdrawal withdrawal = loadWithdrawal(withdrawalId);
        TxAttempt canonical = loadCanonical(withdrawalId);

        if (canonical.getStatus() == TxAttemptStatus.INCLUDED || canonical.getStatus() == TxAttemptStatus.SUCCESS) {
            return canonical;
        }

        if (canonical.getStatus() != TxAttemptStatus.BROADCASTED) {
            throw new InvalidRequestException("Cannot confirm before broadcast. Current canonical status: " + canonical.getStatus());
        }

        canonical.transitionTo(TxAttemptStatus.INCLUDED);
        withdrawal.transitionTo(WithdrawalStatus.W7_INCLUDED);
        withdrawalRepository.save(withdrawal);
        TxAttempt saved = txAttemptRepository.save(canonical);
        log.info("event=retry_replace.simulate_confirmation.done withdrawalId={} attemptId={} status={}", withdrawalId, saved.getId(), saved.getStatus());
        return saved;
    }

    @Transactional
    public Withdrawal simulateFinalization(UUID withdrawalId) {
        log.info("event=retry_replace.simulate_finalization.start withdrawalId={}", withdrawalId);
        Withdrawal w = loadWithdrawal(withdrawalId);

        if (w.getStatus() != WithdrawalStatus.W7_INCLUDED) {
            throw new InvalidRequestException(
                    "finalize requires W7_INCLUDED, current: " + w.getStatus());
        }

        w.transitionTo(WithdrawalStatus.W8_SAFE_FINALIZED);
        withdrawalRepository.save(w);
        log.info("event=retry_replace.simulate_finalization.W8 withdrawalId={}", withdrawalId);

        if (ledgerService != null) {
            Withdrawal settled = ledgerService.settle(w);
            log.info("event=retry_replace.simulate_finalization.done withdrawalId={} status={}",
                    withdrawalId, settled.getStatus());
            return settled;
        }

        w.transitionTo(WithdrawalStatus.W9_LEDGER_POSTED);
        w.transitionTo(WithdrawalStatus.W10_COMPLETED);
        Withdrawal saved = withdrawalRepository.save(w);
        log.info("event=retry_replace.simulate_finalization.done withdrawalId={} status={}",
                withdrawalId, saved.getStatus());
        return saved;
    }

    private boolean isNonceAlreadyIncluded(Withdrawal withdrawal, TxAttempt canonical) {
        ChainAdapter adapter = router.resolve(withdrawal.getChainType());
        if (adapter instanceof EvmRpcAdapter rpcAdapter) {
            long pendingNonce = rpcAdapter.getPendingNonce(canonical.getFromAddress()).longValue();
            return canonical.getNonce() < pendingNonce;
        }
        return false;
    }

    private void ensureWithinAttemptLimit(UUID withdrawalId) {
        int attemptCount = txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId).size();
        if (attemptCount >= 5) {
            log.warn("event=retry_replace.attempt_limit.exceeded withdrawalId={} attemptCount={}", withdrawalId, attemptCount);
            throw new InvalidRequestException("max retry/replace attempts exceeded (5)");
        }
    }

    private long bumpedFee(Long previous, long fallback) {
        long base = previous != null ? previous : fallback;
        return Math.max(base + 1, Math.addExact(base, Math.floorDiv(base, 8)));
    }

    private void broadcast(Withdrawal withdrawal, TxAttempt attempt) {
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
        withdrawal.transitionTo(WithdrawalStatus.W6_BROADCASTED);
        withdrawalRepository.save(withdrawal);
        log.info(
                "event=retry_replace.broadcast.success withdrawalId={} attemptId={} txHash={} attemptStatus={} withdrawalStatus={}",
                withdrawal.getId(),
                attempt.getId(),
                result.txHash(),
                attempt.getStatus(),
                withdrawal.getStatus()
        );
    }

    private Withdrawal loadWithdrawal(UUID withdrawalId) {
        return withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal not found: " + withdrawalId));
    }

    private TxAttempt loadCanonical(UUID withdrawalId) {
        List<TxAttempt> attempts = txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId);
        return attempts.stream()
                .filter(TxAttempt::isCanonical)
                .max(Comparator.comparingInt(TxAttempt::getAttemptNo))
                .orElseThrow(() -> new IllegalStateException("no canonical attempt"));
    }
}
