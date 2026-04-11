package lab.custody.orchestration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class ConfirmationTracker {

    private final ChainAdapterRouter router;
    private final TxAttemptRepository txAttemptRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final ExecutorService executor;
    private final int maxTries;
    private final long pollIntervalSeconds;

    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final Set<UUID> trackingSet = ConcurrentHashMap.newKeySet();
    private final Counter timeoutCounter;

    @Autowired
    public ConfirmationTracker(
            ChainAdapterRouter router,
            TxAttemptRepository txAttemptRepository,
            WithdrawalRepository withdrawalRepository,
            MeterRegistry meterRegistry
    ) {
        this(router, txAttemptRepository, withdrawalRepository, Executors.newCachedThreadPool(), 60, 2, meterRegistry);
    }

    ConfirmationTracker(
            ChainAdapterRouter router,
            TxAttemptRepository txAttemptRepository,
            WithdrawalRepository withdrawalRepository,
            ExecutorService executor,
            int maxTries,
            long pollIntervalSeconds,
            MeterRegistry meterRegistry
    ) {
        this.router = router;
        this.txAttemptRepository = txAttemptRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.executor = executor;
        this.maxTries = maxTries;
        this.pollIntervalSeconds = pollIntervalSeconds;

        Gauge.builder("custody.confirmation_tracker.active_tasks", activeTasks, AtomicInteger::get)
                .description("Number of transactions currently being tracked for confirmation")
                .register(meterRegistry);
        this.timeoutCounter = Counter.builder("custody.confirmation_tracker.timeout.total")
                .description("Total number of confirmation tracking attempts that timed out")
                .register(meterRegistry);
    }

    // Start receipt tracking asynchronously so API callers do not block on chain confirmation time.
    public void startTracking(TxAttempt attempt) {
        startTrackingByAttemptId(attempt.getId());
    }

    // Variant used by demo/manual endpoints when only the attempt id is available.
    // Returns true if tracking was started, false if already in progress (duplicate skipped).
    public boolean startTrackingByAttemptId(UUID attemptId) {
        if (!trackingSet.add(attemptId)) {
            log.debug("Confirmation tracking already active for attempt {}, skipping", attemptId);
            return false;
        }
        activeTasks.incrementAndGet();
        submitWithMdc(() -> {
            try {
                trackAttemptInternal(attemptId);
            } finally {
                trackingSet.remove(attemptId);
                activeTasks.decrementAndGet();
            }
        });
        return true;
    }

    public boolean isTracking(UUID attemptId) {
        return trackingSet.contains(attemptId);
    }

    private void submitWithMdc(Runnable task) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        executor.submit(() -> {
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            } else {
                MDC.clear();
            }
            try {
                task.run();
            } finally {
                MDC.clear();
            }
        });
    }

    // Poll the chain for a receipt and apply confirmation state transitions when a receipt is found.
    // Tracking and broadcasting are intentionally separate responsibilities in this architecture.
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
            while (tries < maxTries) { // default: ~2 minutes polling
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
                TimeUnit.SECONDS.sleep(pollIntervalSeconds);
            }
            // reload attempt and mark timeout
            var timeoutAttemptOpt = txAttemptRepository.findById(attemptId);
            if (timeoutAttemptOpt.isPresent()) {
                TxAttempt timeoutAttempt = timeoutAttemptOpt.get();
                timeoutAttempt.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
                txAttemptRepository.save(timeoutAttempt);
            }
            timeoutCounter.increment();
            log.info("Attempt {} marked FAILED_TIMEOUT after polling", attemptId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Confirmation tracking interrupted for attempt {}", attemptId);
        } catch (Exception e) {
            log.error("Unexpected error in confirmation tracker for attempt {}: {}", attemptId, e.getMessage(), e);
        }
    }
}
