package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmMockAdapter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.txattempt.AttemptExceptionType;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lab.custody.sim.fakechain.FakeChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetryReplaceService {

    private static final long DEFAULT_PRIORITY_FEE = 2_000_000_000L;
    private static final long DEFAULT_MAX_FEE = 20_000_000_000L;

    private final WithdrawalRepository withdrawalRepository;
    private final TxAttemptRepository txAttemptRepository;
    private final AttemptService attemptService;
    private final ChainAdapterRouter router;
    private final FakeChain fakeChain;

    // Retry creates a new canonical attempt after marking the previous canonical attempt as timed out.
    // For EVM, the nonce is re-read from the pending state so the retry uses the latest executable nonce.
    @Transactional
    public TxAttempt retry(UUID withdrawalId) {
        log.info("event=retry_replace.retry.start withdrawalId={}", withdrawalId);
        Withdrawal w = loadWithdrawal(withdrawalId);
        TxAttempt canonical = loadCanonical(withdrawalId);
        ensureWithinAttemptLimit(withdrawalId);
        canonical.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
        canonical.setCanonical(false);

        long nonce = canonical.getNonce() + 1;
        ChainAdapter adapter = router.resolve(w.getChainType());
        if (adapter instanceof EvmRpcAdapter rpcAdapter) {
            nonce = rpcAdapter.getPendingNonce(canonical.getFromAddress()).longValue();
        }

        TxAttempt retried = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), nonce);
        broadcast(w, retried);
        TxAttempt saved = txAttemptRepository.save(retried);
        log.info(
                "event=retry_replace.retry.done withdrawalId={} attemptId={} nonce={} status={} canonical={}",
                withdrawalId,
                saved.getId(),
                saved.getNonce(),
                saved.getStatus(),
                saved.isCanonical()
        );
        return saved;
    }

    // Replace keeps the same nonce and bumps fees so a stuck pending tx can be superseded (RBF-style flow).
    // The previous canonical attempt is preserved in history but no longer considered canonical.
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
        TxAttempt saved = txAttemptRepository.save(replaced);
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

    // Synchronous receipt check path used by labs/manual operations.
    // This updates the canonical attempt/withdrawal based on real RPC receipt data when available.
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

    // Lab helper: drive attempt state transitions without a real chain by consuming scripted fake outcomes.
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

    // Lab helper: simulate the "broadcasted -> included" transition while enforcing state order rules.
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


    // Guard against replace on a nonce that has already moved past pending (would fail with nonce-too-low).
    private boolean isNonceAlreadyIncluded(Withdrawal withdrawal, TxAttempt canonical) {
        ChainAdapter adapter = router.resolve(withdrawal.getChainType());
        if (adapter instanceof EvmRpcAdapter rpcAdapter) {
            long pendingNonce = rpcAdapter.getPendingNonce(canonical.getFromAddress()).longValue();
            return canonical.getNonce() < pendingNonce;
        }
        return false;
    }

    // Bound the number of retries/replaces so failures become visible operationally instead of looping forever.
    private void ensureWithinAttemptLimit(UUID withdrawalId) {
        int attemptCount = txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId).size();
        if (attemptCount >= 5) {
            log.warn("event=retry_replace.attempt_limit.exceeded withdrawalId={} attemptCount={}", withdrawalId, attemptCount);
            throw new InvalidRequestException("max retry/replace attempts exceeded (5)");
        }
    }

    // Increase fees enough to satisfy typical replacement rules while guaranteeing at least +1 wei.
    private long bumpedFee(Long previous, long fallback) {
        long base = previous != null ? previous : fallback;
        // Geth replacement rule(약 +10%)를 만족하도록 12.5% 상향 + 최소 1 wei 증가 보장
        long increased = Math.max(base + 1, Math.addExact(base, Math.floorDiv(base, 8)));
        return increased;
    }

    // Shared broadcast path for retry/replace flows: submit via adapter and reflect broadcast state locally.
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

    // Load helpers keep controller/service methods focused on workflow logic and consistent error messages.
    private Withdrawal loadWithdrawal(UUID withdrawalId) {
        return withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal not found: " + withdrawalId));
    }

    // Canonical attempt means "the current representative tx" for this withdrawal among historical attempts.
    private TxAttempt loadCanonical(UUID withdrawalId) {
        List<TxAttempt> attempts = txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId);
        return attempts.stream()
                .filter(TxAttempt::isCanonical)
                .max(Comparator.comparingInt(TxAttempt::getAttemptNo))
                .orElseThrow(() -> new IllegalStateException("no canonical attempt"));
    }

}
