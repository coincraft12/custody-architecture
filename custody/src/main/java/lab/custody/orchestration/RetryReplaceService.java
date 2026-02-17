package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RetryReplaceService {

    private static final long DEFAULT_PRIORITY_FEE = 2_000_000_000L;
    private static final long DEFAULT_MAX_FEE = 20_000_000_000L;

    private final WithdrawalRepository withdrawalRepository;
    private final TxAttemptRepository txAttemptRepository;
    private final AttemptService attemptService;
    private final ChainAdapterRouter router;
    private final FakeChain fakeChain;

    @Transactional
    public TxAttempt retry(UUID withdrawalId) {
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
        return txAttemptRepository.save(retried);
    }

    @Transactional
    public TxAttempt replace(UUID withdrawalId) {
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
        return txAttemptRepository.save(replaced);
    }

    @Transactional
    public TxAttempt sync(UUID withdrawalId) {
        Withdrawal w = loadWithdrawal(withdrawalId);
        TxAttempt canonical = loadCanonical(withdrawalId);
        ChainAdapter adapter = router.resolve(w.getChainType());
        if (adapter instanceof EvmRpcAdapter rpcAdapter && canonical.getTxHash() != null) {
            var receiptOpt = rpcAdapter.getReceipt(canonical.getTxHash());
            if (receiptOpt.isPresent()) {
                canonical.transitionTo(TxAttemptStatus.INCLUDED);
                if ("0x1".equalsIgnoreCase(receiptOpt.get().getStatus())) {
                    canonical.transitionTo(TxAttemptStatus.SUCCESS);
                    w.transitionTo(WithdrawalStatus.W7_INCLUDED);
                } else {
                    canonical.transitionTo(TxAttemptStatus.FAILED);
                }
            }
        }
        return txAttemptRepository.save(canonical);
    }

    @Transactional
    public TxAttempt simulateBroadcast(UUID withdrawalId) {
        Withdrawal withdrawal = loadWithdrawal(withdrawalId);
        TxAttempt canonical = loadCanonical(withdrawalId);
        FakeChain.NextOutcome outcome = fakeChain.consumeOutcome(withdrawalId);

        if (outcome == FakeChain.NextOutcome.FAIL_SYSTEM) {
            canonical.markException(AttemptExceptionType.FAILED_SYSTEM, "simulated failure");
            canonical.setCanonical(false);
            txAttemptRepository.save(canonical);

            TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), canonical.getNonce());
            return txAttemptRepository.save(newAttempt);
        }

        if (outcome == FakeChain.NextOutcome.REPLACED) {
            canonical.markException(AttemptExceptionType.REPLACED, "simulated replace");
            canonical.transitionTo(TxAttemptStatus.REPLACED);
            canonical.setCanonical(false);
            txAttemptRepository.save(canonical);

            TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), canonical.getNonce());
            return txAttemptRepository.save(newAttempt);
        }

        canonical.transitionTo(TxAttemptStatus.INCLUDED);
        withdrawal.transitionTo(WithdrawalStatus.W7_INCLUDED);
        withdrawalRepository.save(withdrawal);
        return txAttemptRepository.save(canonical);
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
        if (txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId).size() >= 3) {
            throw new InvalidRequestException("max retry/replace attempts exceeded (3)");
        }
    }

    private long bumpedFee(Long previous, long fallback) {
        long base = previous != null ? previous : fallback;
        // Geth replacement rule(약 +10%)를 만족하도록 12.5% 상향 + 최소 1 wei 증가 보장
        long increased = Math.max(base + 1, Math.addExact(base, Math.floorDiv(base, 8)));
        return increased;
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
