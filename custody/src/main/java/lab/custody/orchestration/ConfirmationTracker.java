package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConfirmationTracker {

    private final ChainAdapterRouter router;
    private final TxAttemptRepository txAttemptRepository;
    private final WithdrawalRepository withdrawalRepository;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public void startTracking(TxAttempt attempt) {
        executor.submit(() -> trackAttemptInternal(attempt.getId()));
    }

    public void startTrackingByAttemptId(java.util.UUID attemptId) {
        executor.submit(() -> trackAttemptInternal(attemptId));
    }

    private void trackAttemptInternal(java.util.UUID attemptId) {
        try {
            log.debug("Starting confirmation tracking for attempt {}", attemptId);

            // Reload attempt to get latest data
            var attemptOpt = txAttemptRepository.findById(attemptId);
            if (attemptOpt.isEmpty()) {
                log.warn("Attempt {} not found, aborting tracking", attemptId);
                return;
            }
            TxAttempt attempt = attemptOpt.get();

            var wopt = withdrawalRepository.findById(attempt.getWithdrawalId());
            if (wopt.isEmpty()) {
                log.warn("Withdrawal {} not found for attempt {}, aborting", attempt.getWithdrawalId(), attemptId);
                return;
            }
            Withdrawal withdrawal = wopt.get();

            ChainAdapter adapter = router.resolve(withdrawal.getChainType());
            if (!(adapter instanceof EvmRpcAdapter rpcAdapter)) {
                log.debug("Adapter for chain {} does not support receipt polling", withdrawal.getChainType());
                return; // only EVM RPC currently supported by this tracker
            }

            String txHash = attempt.getTxHash();
            if (txHash == null) {
                log.debug("Attempt {} has no txHash, aborting tracking", attemptId);
                return;
            }

            int tries = 0;
            while (tries < 60) { // ~2 minutes polling
                try {
                    Optional<org.web3j.protocol.core.methods.response.TransactionReceipt> r = rpcAdapter.getReceipt(txHash);
                    if (r.isPresent()) {
                        // reload attempt/withdrawal before updating to avoid stale entity issues
                        var toUpdateAttemptOpt = txAttemptRepository.findById(attemptId);
                        if (toUpdateAttemptOpt.isEmpty()) {
                            log.warn("Attempt {} disappeared before update", attemptId);
                            return;
                        }
                        TxAttempt toUpdateAttempt = toUpdateAttemptOpt.get();
                        toUpdateAttempt.transitionTo(TxAttemptStatus.INCLUDED);
                        txAttemptRepository.save(toUpdateAttempt);

                        var toUpdateWithdrawalOpt = withdrawalRepository.findById(withdrawal.getId());
                        if (toUpdateWithdrawalOpt.isPresent()) {
                            Withdrawal toUpdateWithdrawal = toUpdateWithdrawalOpt.get();
                            toUpdateWithdrawal.transitionTo(WithdrawalStatus.W7_INCLUDED);
                            withdrawalRepository.save(toUpdateWithdrawal);
                        }
                        log.info("Attempt {} marked INCLUDED and withdrawal {} marked INCLUDED", attemptId, withdrawal.getId());
                        return;
                    }
                } catch (Exception e) {
                    log.warn("Error while polling receipt for tx {}: {}", txHash, e.getMessage());
                }
                tries++;
                TimeUnit.SECONDS.sleep(2);
            }
            // reload attempt and mark timeout
            var timeoutAttemptOpt = txAttemptRepository.findById(attemptId);
            if (timeoutAttemptOpt.isPresent()) {
                TxAttempt timeoutAttempt = timeoutAttemptOpt.get();
                timeoutAttempt.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
                txAttemptRepository.save(timeoutAttempt);
            }
            log.info("Attempt {} marked FAILED_TIMEOUT after polling", attemptId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Confirmation tracking interrupted for attempt {}", attemptId);
        } catch (Exception e) {
            log.error("Unexpected error in confirmation tracker for attempt {}: {}", attemptId, e.getMessage(), e);
        }
    }
}
