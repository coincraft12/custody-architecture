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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    // 5-1: 하드코딩 제거 — application.yaml에서 주입
    private final int maxTries;
    private final long pollIntervalMs;

    // 5-2: 확정(finalization) 블록 수 — 0이면 receipt 수신 즉시 W8로 전이
    private final int finalizationBlockCount;
    // 5-2-5: 최대 확정 대기 시간 초과 시 카운터 증가 + 경고 로그
    private final int finalizationTimeoutMinutes;

    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final Set<UUID> trackingSet = ConcurrentHashMap.newKeySet();
    private final Counter timeoutCounter;
    private final Counter finalizationTimeoutCounter;

    @Autowired(required = false)
    private LedgerService ledgerService;

    // 5-1: @Autowired 생성자 — application.yaml / 환경변수로 설정 주입
    @Autowired
    public ConfirmationTracker(
            ChainAdapterRouter router,
            TxAttemptRepository txAttemptRepository,
            WithdrawalRepository withdrawalRepository,
            MeterRegistry meterRegistry,
            // 5-1-2/5-1-4: max-tries (env: CUSTODY_CONFIRMATION_TRACKER_MAX_TRIES)
            @Value("${custody.confirmation-tracker.max-tries:60}") int maxTries,
            // 5-1-2/5-1-4: poll-interval-ms (env: CUSTODY_CONFIRMATION_TRACKER_POLL_INTERVAL_MS)
            @Value("${custody.confirmation-tracker.poll-interval-ms:2000}") long pollIntervalMs,
            // 5-2-1: finalization-block-count (env: CUSTODY_CONFIRMATION_TRACKER_FINALIZATION_BLOCK_COUNT)
            @Value("${custody.confirmation-tracker.finalization-block-count:0}") int finalizationBlockCount,
            // 5-2-5: finalization-timeout-minutes (env: CUSTODY_CONFIRMATION_TRACKER_FINALIZATION_TIMEOUT_MINUTES)
            @Value("${custody.confirmation-tracker.finalization-timeout-minutes:30}") int finalizationTimeoutMinutes
    ) {
        this(router, txAttemptRepository, withdrawalRepository,
                Executors.newCachedThreadPool(), maxTries, pollIntervalMs,
                finalizationBlockCount, finalizationTimeoutMinutes, meterRegistry);
    }

    // Package-private constructor for unit tests
    ConfirmationTracker(
            ChainAdapterRouter router,
            TxAttemptRepository txAttemptRepository,
            WithdrawalRepository withdrawalRepository,
            ExecutorService executor,
            int maxTries,
            long pollIntervalMs,
            int finalizationBlockCount,
            int finalizationTimeoutMinutes,
            MeterRegistry meterRegistry
    ) {
        this.router = router;
        this.txAttemptRepository = txAttemptRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.executor = executor;
        this.maxTries = maxTries;
        this.pollIntervalMs = pollIntervalMs;
        this.finalizationBlockCount = finalizationBlockCount;
        this.finalizationTimeoutMinutes = finalizationTimeoutMinutes;

        Gauge.builder("custody.confirmation_tracker.active_tasks", activeTasks, AtomicInteger::get)
                .description("Number of transactions currently being tracked for confirmation")
                .register(meterRegistry);
        this.timeoutCounter = Counter.builder("custody.confirmation_tracker.timeout.total")
                .description("Total number of confirmation tracking attempts that timed out")
                .register(meterRegistry);
        this.finalizationTimeoutCounter = Counter.builder("custody.confirmation_tracker.finalization_timeout.total")
                .description("Total number of TXs that exceeded the finalization wait timeout")
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
            log.debug("event=confirmation_tracker.skip_duplicate attemptId={}", attemptId);
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
    private void trackAttemptInternal(UUID attemptId) {
        try {
            log.debug("event=confirmation_tracker.start attemptId={}", attemptId);

            var attemptOpt = txAttemptRepository.findById(attemptId);
            if (attemptOpt.isEmpty()) {
                log.warn("event=confirmation_tracker.attempt_not_found attemptId={}", attemptId);
                return;
            }
            TxAttempt attempt = attemptOpt.get();

            var wopt = withdrawalRepository.findById(attempt.getWithdrawalId());
            if (wopt.isEmpty()) {
                log.warn("event=confirmation_tracker.withdrawal_not_found withdrawalId={} attemptId={}",
                        attempt.getWithdrawalId(), attemptId);
                return;
            }
            Withdrawal withdrawal = wopt.get();

            ChainAdapter adapter = router.resolve(withdrawal.getChainType());
            if (!(adapter instanceof EvmRpcAdapter rpcAdapter)) {
                log.debug("event=confirmation_tracker.no_receipt_support chainType={}", withdrawal.getChainType());
                return;
            }

            String txHash = attempt.getTxHash();
            if (txHash == null) {
                log.debug("event=confirmation_tracker.no_tx_hash attemptId={}", attemptId);
                return;
            }

            // ── Phase 1: poll until receipt arrives ──────────────────────────
            int tries = 0;
            while (tries < maxTries) {
                try {
                    Optional<TransactionReceipt> r = rpcAdapter.getReceipt(txHash);
                    if (r.isPresent()) {
                        TransactionReceipt receipt = r.get();

                        // Mark W7_INCLUDED
                        var toUpdateAttemptOpt = txAttemptRepository.findById(attemptId);
                        if (toUpdateAttemptOpt.isEmpty()) {
                            log.warn("event=confirmation_tracker.attempt_gone attemptId={}", attemptId);
                            return;
                        }
                        TxAttempt toUpdateAttempt = toUpdateAttemptOpt.get();
                        toUpdateAttempt.transitionTo(TxAttemptStatus.INCLUDED);
                        txAttemptRepository.save(toUpdateAttempt);

                        var wIncludedOpt = withdrawalRepository.findById(withdrawal.getId());
                        if (wIncludedOpt.isPresent()) {
                            Withdrawal wIncluded = wIncludedOpt.get();
                            wIncluded.transitionTo(WithdrawalStatus.W7_INCLUDED);
                            withdrawalRepository.save(wIncluded);
                        }
                        log.info("event=confirmation_tracker.included attemptId={} withdrawalId={} txHash={}",
                                attemptId, withdrawal.getId(), txHash);

                        // ── Phase 2: wait for finalization ────────────────────
                        waitForFinalization(rpcAdapter, receipt, withdrawal);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("event=confirmation_tracker.receipt_poll_error txHash={} error={}", txHash, e.getMessage());
                }
                tries++;
                if (pollIntervalMs > 0) {
                    TimeUnit.MILLISECONDS.sleep(pollIntervalMs);
                }
            }

            // Receipt never arrived → FAILED_TIMEOUT
            var timeoutAttemptOpt = txAttemptRepository.findById(attemptId);
            if (timeoutAttemptOpt.isPresent()) {
                TxAttempt timeoutAttempt = timeoutAttemptOpt.get();
                timeoutAttempt.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
                txAttemptRepository.save(timeoutAttempt);
            }
            timeoutCounter.increment();
            log.info("event=confirmation_tracker.timeout attemptId={} tries={}", attemptId, tries);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=confirmation_tracker.interrupted attemptId={}", attemptId);
        } catch (Exception e) {
            log.error("event=confirmation_tracker.unexpected_error attemptId={} error={}", attemptId, e.getMessage(), e);
        }
    }

    /**
     * 5-2-2~5-2-5: receipt 수신 후 finalizationBlockCount 블록 경과를 확인하여
     * W8_SAFE_FINALIZED 전이 + LedgerService.settle() 호출.
     *
     * <p>finalizationBlockCount == 0 이면 즉시 W8로 전이한다 (mock/dev 환경 기본값).
     * finalizationTimeoutMinutes 초과 시 카운터를 증가시키고 경고 로그를 남긴다.
     */
    private void waitForFinalization(
            EvmRpcAdapter rpcAdapter,
            TransactionReceipt receipt,
            Withdrawal withdrawal
    ) throws InterruptedException {

        long receiptBlock = receipt.getBlockNumber() != null
                ? receipt.getBlockNumber().longValue() : -1L;

        // finalizationBlockCount == 0: 즉시 확정 처리 (mock 모드 기본값)
        if (finalizationBlockCount <= 0 || receiptBlock < 0) {
            finalizeWithdrawal(withdrawal);
            return;
        }

        // 5-2-3/5-2-4: finalizationBlockCount 블록 경과 대기
        Instant deadline = Instant.now().plus(finalizationTimeoutMinutes, ChronoUnit.MINUTES);
        log.info("event=confirmation_tracker.finalization.waiting withdrawalId={} receiptBlock={} required={}",
                withdrawal.getId(), receiptBlock, finalizationBlockCount);

        while (Instant.now().isBefore(deadline)) {
            try {
                long currentBlock = rpcAdapter.getBlockNumber();
                long elapsed = currentBlock - receiptBlock;
                if (elapsed >= finalizationBlockCount) {
                    log.info("event=confirmation_tracker.finalization.reached withdrawalId={} currentBlock={} elapsed={}",
                            withdrawal.getId(), currentBlock, elapsed);
                    finalizeWithdrawal(withdrawal);
                    return;
                }
                log.debug("event=confirmation_tracker.finalization.pending withdrawalId={} currentBlock={} elapsed={}/{}",
                        withdrawal.getId(), currentBlock, elapsed, finalizationBlockCount);
            } catch (Exception e) {
                log.warn("event=confirmation_tracker.finalization.block_fetch_error withdrawalId={} error={}",
                        withdrawal.getId(), e.getMessage());
            }
            if (pollIntervalMs > 0) {
                TimeUnit.MILLISECONDS.sleep(pollIntervalMs);
            }
        }

        // 5-2-5: finalization-timeout-minutes 초과
        finalizationTimeoutCounter.increment();
        log.warn("event=confirmation_tracker.finalization.timeout withdrawalId={} timeoutMinutes={}",
                withdrawal.getId(), finalizationTimeoutMinutes);
    }

    /**
     * W8_SAFE_FINALIZED 전이 + LedgerService.settle() (W9→W10).
     */
    private void finalizeWithdrawal(Withdrawal withdrawal) {
        var wFinalizeOpt = withdrawalRepository.findById(withdrawal.getId());
        if (wFinalizeOpt.isEmpty()) {
            log.warn("event=confirmation_tracker.finalization.withdrawal_gone withdrawalId={}", withdrawal.getId());
            return;
        }
        Withdrawal wFinalize = wFinalizeOpt.get();
        wFinalize.transitionTo(WithdrawalStatus.W8_SAFE_FINALIZED);
        wFinalize = withdrawalRepository.save(wFinalize);
        log.info("event=confirmation_tracker.finalized withdrawalId={}", wFinalize.getId());

        // LedgerService가 주입된 경우 W9→W10 정산 처리
        if (ledgerService != null) {
            try {
                ledgerService.settle(wFinalize);
                log.info("event=confirmation_tracker.settled withdrawalId={}", wFinalize.getId());
            } catch (Exception e) {
                log.error("event=confirmation_tracker.settle_failed withdrawalId={} error={}",
                        wFinalize.getId(), e.getMessage(), e);
            }
        }
    }
}
