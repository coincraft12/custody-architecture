package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmationTrackerTest {

    @Mock
    private ChainAdapterRouter router;

    @Mock
    private TxAttemptRepository txAttemptRepository;

    @Mock
    private WithdrawalRepository withdrawalRepository;

    @Mock
    private EvmRpcAdapter rpcAdapter;

    @Test
    void trackAttemptInternal_whenReceiptNeverArrives_marksAttemptFailedTimeout() {
        UUID withdrawalId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        Withdrawal withdrawal = Withdrawal.requested("idem-confirmation-timeout", ChainType.EVM, "0xfrom", "0xto", "ETH", 1L);
        withdrawal.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        TxAttempt attempt = TxAttempt.created(withdrawalId, 1, "0xfrom", 0L, true);
        ReflectionTestUtils.setField(attempt, "id", attemptId);
        attempt.setTxHash("0xtx-timeout");
        attempt.transitionTo(TxAttemptStatus.BROADCASTED);

        ConfirmationTracker tracker = new ConfirmationTracker(
                router,
                txAttemptRepository,
                withdrawalRepository,
                Executors.newSingleThreadExecutor(),
                1,   // maxTries
                0,   // pollIntervalMs (5-1: ms 단위로 변경)
                0,   // finalizationBlockCount (5-2: 0=즉시 확정)
                30,  // finalizationTimeoutMinutes
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        when(txAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt), Optional.of(attempt));
        when(withdrawalRepository.findById(withdrawalId)).thenReturn(Optional.of(withdrawal));
        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getReceipt("0xtx-timeout")).thenReturn(Optional.empty());
        when(txAttemptRepository.save(attempt)).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(tracker, "trackAttemptInternal", attemptId);

        assertThat(attempt.getStatus()).isEqualTo(TxAttemptStatus.FAILED_TIMEOUT);
        verify(txAttemptRepository).save(attempt);
    }
}
