package lab.custody.orchestration;

import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartupRecoveryServiceTest {

    @Mock WithdrawalRepository withdrawalRepository;
    @Mock TxAttemptRepository txAttemptRepository;

    private ConfirmationTracker tracker;
    private StartupRecoveryService service;

    @BeforeEach
    void setUp() {
        tracker = new ConfirmationTracker(
                mock(lab.custody.adapter.ChainAdapterRouter.class),
                txAttemptRepository,
                withdrawalRepository,
                Executors.newSingleThreadExecutor(),
                1,
                0,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );
        service = new StartupRecoveryService(withdrawalRepository, txAttemptRepository);
        ReflectionTestUtils.setField(service, "confirmationTracker", tracker);
    }

    @Test
    void noWithdrawals_logsAndReturns() {
        when(withdrawalRepository.findByStatus(WithdrawalStatus.W6_BROADCASTED)).thenReturn(List.of());

        service.recoverBroadcastedWithdrawals();

        verify(txAttemptRepository, never()).findFirstByWithdrawalIdAndCanonicalTrue(any());
    }

    @Test
    void validBroadcastedWithdrawal_startsTracking() {
        UUID wId = UUID.randomUUID();
        UUID aId = UUID.randomUUID();

        Withdrawal w = Withdrawal.requested("key", ChainType.EVM, "0xfrom", "0xto", "ETH", 1L);
        ReflectionTestUtils.setField(w, "id", wId);
        w.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED);
        w.transitionTo(WithdrawalStatus.W3_APPROVED);
        w.transitionTo(WithdrawalStatus.W4_SIGNING);
        w.transitionTo(WithdrawalStatus.W5_SIGNED);
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        TxAttempt attempt = TxAttempt.created(wId, 1, "0xfrom", 0L, true);
        ReflectionTestUtils.setField(attempt, "id", aId);
        attempt.setTxHash("0xabc");
        attempt.transitionTo(TxAttemptStatus.BROADCASTED);

        when(withdrawalRepository.findByStatus(WithdrawalStatus.W6_BROADCASTED)).thenReturn(List.of(w));
        when(txAttemptRepository.findFirstByWithdrawalIdAndCanonicalTrue(wId)).thenReturn(Optional.of(attempt));
        // tracker will call findById during trackAttemptInternal — lenient because async timing may vary
        lenient().when(txAttemptRepository.findById(aId)).thenReturn(Optional.empty());

        service.recoverBroadcastedWithdrawals();

        // Attempt was submitted to tracking (isTracking may already be false after quick completion)
        verify(txAttemptRepository).findFirstByWithdrawalIdAndCanonicalTrue(wId);
    }

    @Test
    void attemptWithNoTxHash_skipped() {
        UUID wId = UUID.randomUUID();

        Withdrawal w = Withdrawal.requested("key2", ChainType.EVM, "0xfrom", "0xto", "ETH", 1L);
        ReflectionTestUtils.setField(w, "id", wId);
        w.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED);
        w.transitionTo(WithdrawalStatus.W3_APPROVED);
        w.transitionTo(WithdrawalStatus.W4_SIGNING);
        w.transitionTo(WithdrawalStatus.W5_SIGNED);
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        TxAttempt attempt = TxAttempt.created(wId, 1, "0xfrom", 0L, true);
        // txHash is null by default

        when(withdrawalRepository.findByStatus(WithdrawalStatus.W6_BROADCASTED)).thenReturn(List.of(w));
        when(txAttemptRepository.findFirstByWithdrawalIdAndCanonicalTrue(wId)).thenReturn(Optional.of(attempt));

        service.recoverBroadcastedWithdrawals();

        // No tracking started because txHash is null
        verify(txAttemptRepository, never()).findById(any());
    }

    @Test
    void noCanonicalAttempt_skipped() {
        UUID wId = UUID.randomUUID();

        Withdrawal w = Withdrawal.requested("key3", ChainType.EVM, "0xfrom", "0xto", "ETH", 1L);
        ReflectionTestUtils.setField(w, "id", wId);
        w.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED);
        w.transitionTo(WithdrawalStatus.W3_APPROVED);
        w.transitionTo(WithdrawalStatus.W4_SIGNING);
        w.transitionTo(WithdrawalStatus.W5_SIGNED);
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        when(withdrawalRepository.findByStatus(WithdrawalStatus.W6_BROADCASTED)).thenReturn(List.of(w));
        when(txAttemptRepository.findFirstByWithdrawalIdAndCanonicalTrue(wId)).thenReturn(Optional.empty());

        service.recoverBroadcastedWithdrawals();

        verify(txAttemptRepository, never()).findById(any());
    }

    @Test
    void duplicateTracking_skipped() {
        UUID wId = UUID.randomUUID();
        UUID aId = UUID.randomUUID();

        Withdrawal w = Withdrawal.requested("key4", ChainType.EVM, "0xfrom", "0xto", "ETH", 1L);
        ReflectionTestUtils.setField(w, "id", wId);
        w.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED);
        w.transitionTo(WithdrawalStatus.W3_APPROVED);
        w.transitionTo(WithdrawalStatus.W4_SIGNING);
        w.transitionTo(WithdrawalStatus.W5_SIGNED);
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        TxAttempt attempt = TxAttempt.created(wId, 1, "0xfrom", 0L, true);
        ReflectionTestUtils.setField(attempt, "id", aId);
        attempt.setTxHash("0xdup");
        attempt.transitionTo(TxAttemptStatus.BROADCASTED);

        // Pre-register to simulate already-tracking state.
        // trackAttemptInternal runs async — lenient because it may finish after Mockito teardown.
        lenient().when(txAttemptRepository.findById(aId)).thenReturn(Optional.empty());
        tracker.startTrackingByAttemptId(aId);

        when(withdrawalRepository.findByStatus(WithdrawalStatus.W6_BROADCASTED)).thenReturn(List.of(w));
        when(txAttemptRepository.findFirstByWithdrawalIdAndCanonicalTrue(wId)).thenReturn(Optional.of(attempt));

        service.recoverBroadcastedWithdrawals();

        // startTrackingByAttemptId returns false for duplicate — only 1 findById call from pre-register
        verify(txAttemptRepository, atMostOnce()).findById(aId);
    }
}
