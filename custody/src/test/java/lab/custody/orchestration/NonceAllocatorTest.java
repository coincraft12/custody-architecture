package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.nonce.NonceReservation;
import lab.custody.domain.nonce.NonceReservationRepository;
import lab.custody.domain.nonce.NonceReservationStatus;
import lab.custody.domain.withdrawal.ChainType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NonceAllocatorTest {

    @Mock
    private NonceReservationRepository nonceReservationRepository;

    @Mock
    private ChainAdapterRouter router;

    @Mock
    private EvmRpcAdapter rpcAdapter;

    @Mock
    private ChainAdapter nonEvmAdapter;

    private NonceAllocator nonceAllocator;

    @BeforeEach
    void setUp() {
        nonceAllocator = new NonceAllocator(nonceReservationRepository, router);
        ReflectionTestUtils.setField(nonceAllocator, "expiryMinutes", 10);
    }

    @Test
    void reserve_firstCall_usesRpcPendingNonce() {
        UUID withdrawalId = UUID.randomUUID();

        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getPendingNonce("0xfrom")).thenReturn(BigInteger.ZERO);
        when(nonceReservationRepository.findActiveWithLock(ChainType.EVM, "0xfrom"))
                .thenReturn(List.of());
        when(nonceReservationRepository.save(any(NonceReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation reservation = nonceAllocator.reserve(ChainType.EVM, "0xfrom", withdrawalId);

        assertThat(reservation.getNonce()).isEqualTo(0L);
        assertThat(reservation.getWithdrawalId()).isEqualTo(withdrawalId);
        assertThat(reservation.getStatus()).isEqualTo(NonceReservationStatus.RESERVED);
        assertThat(reservation.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void reserve_whenActiveNonceExists_allocatesNextNonce() {
        NonceReservation existing = NonceReservation.reserve(ChainType.EVM, "0xaddr", 2L, UUID.randomUUID(), Instant.now().plusSeconds(600));

        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getPendingNonce("0xaddr")).thenReturn(BigInteger.ZERO);
        when(nonceReservationRepository.findActiveWithLock(ChainType.EVM, "0xaddr"))
                .thenReturn(List.of(existing));
        when(nonceReservationRepository.save(any(NonceReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation reservation = nonceAllocator.reserve(ChainType.EVM, "0xaddr", UUID.randomUUID());

        assertThat(reservation.getNonce()).isEqualTo(3L);
    }

    @Test
    void reserve_normalizesAddressBeforeQuerying() {
        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getPendingNonce("0xfrom")).thenReturn(BigInteger.ONE);
        when(nonceReservationRepository.findActiveWithLock(ChainType.EVM, "0xfrom"))
                .thenReturn(List.of());
        when(nonceReservationRepository.save(any(NonceReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation reservation = nonceAllocator.reserve(ChainType.EVM, "  0XFROM  ", UUID.randomUUID());

        assertThat(reservation.getFromAddress()).isEqualTo("0xfrom");
        assertThat(reservation.getNonce()).isEqualTo(1L);
    }

    @Test
    void reserve_activeNonceHigherThanRpc_usesActiveMax() {
        // RPC pending=1 이지만 DB에 nonce=5인 활성 예약 존재 → 6을 할당
        NonceReservation existing = NonceReservation.reserve(ChainType.EVM, "0xaddr", 5L, UUID.randomUUID(), Instant.now().plusSeconds(600));

        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getPendingNonce("0xaddr")).thenReturn(BigInteger.ONE);
        when(nonceReservationRepository.findActiveWithLock(ChainType.EVM, "0xaddr"))
                .thenReturn(List.of(existing));
        when(nonceReservationRepository.save(any(NonceReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation reservation = nonceAllocator.reserve(ChainType.EVM, "0xaddr", UUID.randomUUID());

        assertThat(reservation.getNonce()).isEqualTo(6L);
    }

    @Test
    void reserve_forNonEvmChain_startsFromZeroWithoutRpcNonce() {
        when(router.resolve(ChainType.BFT)).thenReturn(nonEvmAdapter);
        when(nonceReservationRepository.findActiveWithLock(ChainType.BFT, "bft-sender"))
                .thenReturn(List.of());
        when(nonceReservationRepository.save(any(NonceReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation reservation = nonceAllocator.reserve(ChainType.BFT, "bft-sender", UUID.randomUUID());

        assertThat(reservation.getNonce()).isEqualTo(0L);
    }

    @Test
    void commit_and_release_updateReservationState() {
        UUID reservationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        NonceReservation reservation = NonceReservation.reserve(ChainType.EVM, "0xfrom", 9L, UUID.randomUUID(), null);
        ReflectionTestUtils.setField(reservation, "id", reservationId);

        when(nonceReservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(nonceReservationRepository.save(any(NonceReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation committed = nonceAllocator.commit(reservationId, attemptId);
        assertThat(committed.getStatus()).isEqualTo(NonceReservationStatus.COMMITTED);
        assertThat(committed.getAttemptId()).isEqualTo(attemptId);

        NonceReservation released = nonceAllocator.release(reservationId);
        assertThat(released.getStatus()).isEqualTo(NonceReservationStatus.RELEASED);
    }
}
