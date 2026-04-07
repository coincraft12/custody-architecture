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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    }

    @Test
    void reserve_firstCall_usesRpcPendingNonce() {
        UUID withdrawalId = UUID.randomUUID();

        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getPendingNonce("0xfrom")).thenReturn(BigInteger.ZERO);
        when(nonceReservationRepository.findMaxActiveNonce(ChainType.EVM, "0xfrom")).thenReturn(Optional.empty());
        when(nonceReservationRepository.save(any(NonceReservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation reservation = nonceAllocator.reserve(ChainType.EVM, "0xfrom", withdrawalId);

        assertThat(reservation.getNonce()).isEqualTo(0L);
        assertThat(reservation.getWithdrawalId()).isEqualTo(withdrawalId);
        assertThat(reservation.getStatus()).isEqualTo(NonceReservationStatus.RESERVED);
    }

    @Test
    void reserve_whenActiveNonceExists_allocatesNextNonce() {
        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getPendingNonce("0xaddr")).thenReturn(BigInteger.ZERO);
        when(nonceReservationRepository.findMaxActiveNonce(ChainType.EVM, "0xaddr")).thenReturn(Optional.of(2L));
        when(nonceReservationRepository.save(any(NonceReservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation reservation = nonceAllocator.reserve(ChainType.EVM, "0xaddr", UUID.randomUUID());

        assertThat(reservation.getNonce()).isEqualTo(3L);
    }

    @Test
    void reserve_normalizesAddressBeforeQuerying() {
        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getPendingNonce("0xfrom")).thenReturn(BigInteger.ONE);
        when(nonceReservationRepository.findMaxActiveNonce(ChainType.EVM, "0xfrom")).thenReturn(Optional.empty());
        when(nonceReservationRepository.save(any(NonceReservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation reservation = nonceAllocator.reserve(ChainType.EVM, "  0XFROM  ", UUID.randomUUID());

        assertThat(reservation.getFromAddress()).isEqualTo("0xfrom");
        assertThat(reservation.getNonce()).isEqualTo(1L);
    }

    @Test
    void reserve_onConflict_retriesWithNextNonce() {
        when(router.resolve(ChainType.EVM)).thenReturn(rpcAdapter);
        when(rpcAdapter.getPendingNonce("0xrace")).thenReturn(BigInteger.ZERO);
        when(nonceReservationRepository.findMaxActiveNonce(ChainType.EVM, "0xrace")).thenReturn(Optional.empty());
        when(nonceReservationRepository.save(any(NonceReservation.class)))
                .thenThrow(new DataIntegrityViolationException("conflict"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation reservation = nonceAllocator.reserve(ChainType.EVM, "0xrace", UUID.randomUUID());

        assertThat(reservation.getNonce()).isEqualTo(1L);
    }

    @Test
    void reserve_forNonEvmChain_startsFromZeroWithoutRpcNonce() {
        when(router.resolve(ChainType.BFT)).thenReturn(nonEvmAdapter);
        when(nonceReservationRepository.findMaxActiveNonce(ChainType.BFT, "bft-sender")).thenReturn(Optional.empty());
        when(nonceReservationRepository.save(any(NonceReservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

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
        when(nonceReservationRepository.save(any(NonceReservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NonceReservation committed = nonceAllocator.commit(reservationId, attemptId);
        assertThat(committed.getStatus()).isEqualTo(NonceReservationStatus.COMMITTED);
        assertThat(committed.getAttemptId()).isEqualTo(attemptId);

        NonceReservation released = nonceAllocator.release(reservationId);
        assertThat(released.getStatus()).isEqualTo(NonceReservationStatus.RELEASED);
    }
}
