package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.nonce.NonceReservation;
import lab.custody.domain.txattempt.AttemptExceptionType;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lab.custody.sim.fakechain.FakeChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryReplaceServiceTest {

    @Mock
    private WithdrawalRepository withdrawalRepository;

    @Mock
    private TxAttemptRepository txAttemptRepository;

    @Mock
    private AttemptService attemptService;

    @Mock
    private ChainAdapterRouter router;

    @Mock
    private FakeChain fakeChain;

    @Mock
    private EvmRpcAdapter rpcAdapter;

    @Mock
    private NonceAllocator nonceAllocator;

    private RetryReplaceService retryReplaceService;

    @BeforeEach
    void setUp() {
        retryReplaceService = new RetryReplaceService(
                withdrawalRepository,
                txAttemptRepository,
                attemptService,
                router,
                fakeChain,
                nonceAllocator
        );
    }

    @Test
    void retry_usesLatestPendingNonce_andCreatesNewCanonicalAttempt() {
        UUID withdrawalId = UUID.randomUUID();
        Withdrawal withdrawal = broadcastedWithdrawal("idem-retry", "0xfrom");
        ReflectionTestUtils.setField(withdrawal, "id", withdrawalId);

        TxAttempt canonical = canonicalAttempt(withdrawalId, 1, 3L);
        TxAttempt retried = TxAttempt.created(withdrawalId, 2, "0xfrom", 7L, true);
        ReflectionTestUtils.setField(retried, "id", UUID.randomUUID());

        when(withdrawalRepository.findById(withdrawalId)).thenReturn(Optional.of(withdrawal));
        when(txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId)).thenReturn(List.of(canonical));
        NonceReservation reservation = NonceReservation.reserve(ChainType.EVM, "0xfrom", 7L, withdrawalId, null);
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        when(nonceAllocator.reserve(ChainType.EVM, "0xfrom", withdrawalId)).thenReturn(reservation);
        when(attemptService.createAttempt(withdrawalId, "0xfrom", 7L)).thenReturn(retried);
        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.broadcast(any())).thenReturn(new ChainAdapter.BroadcastResult("0xtx-retry", true));
        when(txAttemptRepository.save(any(TxAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(nonceAllocator.commit(reservation.getId(), retried.getId())).thenReturn(reservation);
        when(nonceAllocator.releaseByAttemptIdIfPresent(canonical.getId())).thenReturn(Optional.empty());

        TxAttempt saved = retryReplaceService.retry(withdrawalId);

        assertThat(canonical.getStatus()).isEqualTo(TxAttemptStatus.FAILED_TIMEOUT);
        assertThat(canonical.isCanonical()).isFalse();
        assertThat(saved.getNonce()).isEqualTo(7L);
        assertThat(saved.isCanonical()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(TxAttemptStatus.BROADCASTED);
        assertThat(saved.getTxHash()).isEqualTo("0xtx-retry");
        verify(attemptService).createAttempt(withdrawalId, "0xfrom", 7L);
    }

    @Test
    void replace_reusesSameNonce_bumpsFees_andSwitchesCanonicalAttempt() {
        UUID withdrawalId = UUID.randomUUID();
        Withdrawal withdrawal = broadcastedWithdrawal("idem-replace", "0xfrom");
        ReflectionTestUtils.setField(withdrawal, "id", withdrawalId);

        TxAttempt canonical = canonicalAttempt(withdrawalId, 1, 5L);
        canonical.setFeeParams(100L, 200L);

        TxAttempt replaced = TxAttempt.created(withdrawalId, 2, "0xfrom", 5L, true);
        ReflectionTestUtils.setField(replaced, "id", UUID.randomUUID());

        when(withdrawalRepository.findById(withdrawalId)).thenReturn(Optional.of(withdrawal));
        when(txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId)).thenReturn(List.of(canonical));
        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getPendingNonce("0xfrom")).thenReturn(BigInteger.valueOf(5L));
        when(attemptService.createAttempt(withdrawalId, "0xfrom", 5L)).thenReturn(replaced);
        when(rpcAdapter.broadcast(any())).thenReturn(new ChainAdapter.BroadcastResult("0xtx-replace", true));
        when(txAttemptRepository.save(any(TxAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(nonceAllocator.rebindAttemptIfPresent(canonical.getId(), replaced.getId())).thenReturn(Optional.empty());

        TxAttempt saved = retryReplaceService.replace(withdrawalId);

        assertThat(canonical.getStatus()).isEqualTo(TxAttemptStatus.REPLACED);
        assertThat(canonical.isCanonical()).isFalse();
        assertThat(canonical.getExceptionType()).isEqualTo(AttemptExceptionType.REPLACED);
        assertThat(saved.getNonce()).isEqualTo(5L);
        assertThat(saved.isCanonical()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(TxAttemptStatus.BROADCASTED);
        assertThat(saved.getMaxPriorityFeePerGas()).isGreaterThan(100L);
        assertThat(saved.getMaxFeePerGas()).isGreaterThan(200L);
        verify(attemptService).createAttempt(withdrawalId, "0xfrom", 5L);
    }

    private Withdrawal broadcastedWithdrawal(String idempotencyKey, String fromAddress) {
        Withdrawal withdrawal = Withdrawal.requested(idempotencyKey, ChainType.EVM, fromAddress, "0xto", "ETH", 1L);
        withdrawal.transitionTo(WithdrawalStatus.W6_BROADCASTED);
        return withdrawal;
    }

    private TxAttempt canonicalAttempt(UUID withdrawalId, int attemptNo, long nonce) {
        TxAttempt attempt = TxAttempt.created(withdrawalId, attemptNo, "0xfrom", nonce, true);
        ReflectionTestUtils.setField(attempt, "id", UUID.randomUUID());
        attempt.setTxHash("0xtx-current");
        attempt.transitionTo(TxAttemptStatus.BROADCASTED);
        return attempt;
    }
}
