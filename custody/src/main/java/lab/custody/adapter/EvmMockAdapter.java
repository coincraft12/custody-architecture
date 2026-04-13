package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import lab.custody.orchestration.ConfirmationTracker;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lab.custody.adapter.prepared.EvmMockPreparedTx;
import lab.custody.adapter.prepared.PreparedTx;

import java.util.Set;
import java.util.UUID;

/**
 * 5-4-1: EvmMockAdapter — broadcast 후 auto-confirm-delay-ms(ms) 지연 뒤
 *         W7→W8→W10 전이를 자동으로 실행하는 옵션 추가.
 *
 * <p>custody.mock.auto-confirm-delay-ms=0(기본값) 이면 자동 확인 비활성화.
 * 0보다 큰 값이면 별도 스레드에서 지연 후 ConfirmationTracker에 추적을 위임한다.
 */
@Component
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class EvmMockAdapter implements ChainAdapter {

    // 5-4-2: application.yaml custody.mock.auto-confirm-delay-ms (기본값 0 = 비활성)
    private final long autoConfirmDelayMs;

    @Autowired(required = false)
    @Lazy
    private ConfirmationTracker confirmationTracker;

    @Autowired(required = false)
    private TxAttemptRepository txAttemptRepository;

    @Autowired(required = false)
    private WithdrawalRepository withdrawalRepository;

    public EvmMockAdapter(
            @Value("${custody.mock.auto-confirm-delay-ms:0}") long autoConfirmDelayMs) {
        this.autoConfirmDelayMs = autoConfirmDelayMs;
    }

    @Override
    public BroadcastResult broadcast(BroadcastCommand command) {
        String txHash = "0xEVM_MOCK_" + UUID.randomUUID().toString().substring(0, 8);
        BroadcastResult result = new BroadcastResult(txHash, true);

        // 5-4-1: auto-confirm-delay-ms > 0이면 별도 스레드에서 자동 확인 트리거
        if (autoConfirmDelayMs > 0 && confirmationTracker != null && txAttemptRepository != null) {
            triggerAutoConfirm(command, txHash);
        }

        return result;
    }

    /**
     * 5-4-1: broadcast 후 autoConfirmDelayMs 지연 뒤
     *         ConfirmationTracker를 통해 W7→W8→W10 전이를 자동으로 실행한다.
     *
     * <p>TxAttempt는 broadcast() 호출 직후 아직 DB에 저장되지 않았을 수 있으므로
     * 지연 후 txHash로 attempt를 조회하여 추적을 시작한다.
     */
    private void triggerAutoConfirm(BroadcastCommand command, String txHash) {
        Thread.ofVirtual().start(() -> {
            try {
                if (autoConfirmDelayMs > 0) {
                    Thread.sleep(autoConfirmDelayMs);
                }
                // txHash로 TxAttempt를 조회하여 추적 시작
                txAttemptRepository.findByTxHash(txHash).ifPresentOrElse(
                        attempt -> {
                            log.info("event=mock_auto_confirm.start attemptId={} txHash={} delayMs={}",
                                    attempt.getId(), txHash, autoConfirmDelayMs);
                            confirmationTracker.startTrackingByAttemptId(attempt.getId());
                        },
                        () -> log.warn("event=mock_auto_confirm.attempt_not_found txHash={}", txHash)
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("event=mock_auto_confirm.interrupted txHash={}", txHash);
            } catch (Exception e) {
                log.error("event=mock_auto_confirm.error txHash={} error={}", txHash, e.getMessage(), e);
            }
        });
    }

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }

    @Override
    public Set<ChainAdapterCapability> capabilities() {
        return Set.of();
    }

    /**
     * 17-8: prepareSend() — EVM mock, captures fromAddress for auto-confirm trigger.
     */
    @Override
    public PreparedTx prepareSend(SendRequest request) {
        return new EvmMockPreparedTx(request.fromAddress(), request.toAddress());
    }

    /**
     * 17-8: broadcast(PreparedTx) — generate a mock txHash and optionally trigger auto-confirm.
     */
    @Override
    public BroadcastResult broadcast(PreparedTx prepared) {
        String txHash = "0xEVM_MOCK_" + UUID.randomUUID().toString().substring(0, 8);

        if (autoConfirmDelayMs > 0 && confirmationTracker != null && txAttemptRepository != null) {
            triggerAutoConfirmByHash(txHash);
        }

        return new BroadcastResult(txHash, true);
    }

    /**
     * 17-8: getTxStatus() — mock always returns FINALIZED for any tx.
     */
    @Override
    public TxStatusSnapshot getTxStatus(String txHash) {
        // EVM mock: treat any hash as immediately finalized (consistent with mock behaviour)
        return new TxStatusSnapshot(TxStatusSnapshot.TxStatus.FINALIZED, null, null, null);
    }

    @Override
    public HeadsSnapshot getHeads() {
        return new HeadsSnapshot(0L, null, null, System.currentTimeMillis());
    }

    /** Auto-confirm trigger for broadcast(PreparedTx) path. */
    private void triggerAutoConfirmByHash(String txHash) {
        Thread.ofVirtual().start(() -> {
            try {
                if (autoConfirmDelayMs > 0) {
                    Thread.sleep(autoConfirmDelayMs);
                }
                txAttemptRepository.findByTxHash(txHash).ifPresentOrElse(
                        attempt -> {
                            log.info("event=mock_auto_confirm.start attemptId={} txHash={} delayMs={}",
                                    attempt.getId(), txHash, autoConfirmDelayMs);
                            confirmationTracker.startTrackingByAttemptId(attempt.getId());
                        },
                        () -> log.warn("event=mock_auto_confirm.attempt_not_found txHash={}", txHash)
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("event=mock_auto_confirm.interrupted txHash={}", txHash);
            } catch (Exception e) {
                log.error("event=mock_auto_confirm.error txHash={} error={}", txHash, e.getMessage(), e);
            }
        });
    }

}
