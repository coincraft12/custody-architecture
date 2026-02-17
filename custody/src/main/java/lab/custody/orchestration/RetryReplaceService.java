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
            nonce = rpcAdapter.getPendingNonce(rpcAdapter.getSenderAddress()).longValue();
        }

        TxAttempt retried = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), nonce);
        broadcast(w, retried);
        return txAttemptRepository.save(retried);
    }

    @Transactional
    public TxAttempt replace(UUID withdrawalId) {
        Withdrawal w = loadWithdrawal(withdrawalId);
        TxAttempt canonical = loadCanonical(withdrawalId);
        canonical.transitionTo(TxAttemptStatus.REPLACED);
        canonical.markException(AttemptExceptionType.REPLACED, "fee bump replacement");
        canonical.setCanonical(false);
        txAttemptRepository.save(canonical);

        TxAttempt replaced = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), canonical.getNonce());
        replaced.setFeeParams(4_000_000_000L, 40_000_000_000L);
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

    private void ensureWithinAttemptLimit(UUID withdrawalId) {
        if (txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId).size() >= 3) {
            throw new InvalidRequestException("max retry/replace attempts exceeded (3)");
        }
    }

    private void broadcast(Withdrawal withdrawal, TxAttempt attempt) {
        ChainAdapter.BroadcastResult result = router.resolve(withdrawal.getChainType()).broadcast(
                new ChainAdapter.BroadcastCommand(
                        withdrawal.getId(),
                        withdrawal.getFromAddress(),
                        withdrawal.getToAddress(),
                        withdrawal.getAsset(),
                        withdrawal.getAmount(),
                        attempt.getNonce()
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
