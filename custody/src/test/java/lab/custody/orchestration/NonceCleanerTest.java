package lab.custody.orchestration;

import lab.custody.domain.nonce.NonceReservation;
import lab.custody.domain.nonce.NonceReservationRepository;
import lab.custody.domain.nonce.NonceReservationStatus;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.ChainType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NonceCleanerTest {

    @Mock
    private NonceReservationRepository nonceReservationRepository;

    @Mock
    private TxAttemptRepository txAttemptRepository;

    private NonceCleaner nonceCleaner;

    @BeforeEach
    void setUp() {
        nonceCleaner = new NonceCleaner(nonceReservationRepository, txAttemptRepository);
        ReflectionTestUtils.setField(nonceCleaner, "expiryMinutes", 10);
    }

    @Test
    void clean_만료예약없으면_아무것도하지않는다() {
        when(nonceReservationRepository.findByStatusAndExpiresAtLessThan(
                eq(NonceReservationStatus.RESERVED), any(Instant.class)))
                .thenReturn(List.of());

        nonceCleaner.clean();

        verify(txAttemptRepository, never()).findById(any());
    }

    @Test
    void clean_attemptId없는만료예약_EXPIRED로전이() {
        NonceReservation reservation = reservedWithoutAttempt();

        when(nonceReservationRepository.findByStatusAndExpiresAtLessThan(
                eq(NonceReservationStatus.RESERVED), any(Instant.class)))
                .thenReturn(List.of(reservation));

        nonceCleaner.clean();

        assertThat(reservation.getStatus()).isEqualTo(NonceReservationStatus.EXPIRED);
        verify(txAttemptRepository, never()).findById(any());
    }

    @Test
    void clean_attemptId있는만료예약_EXPIRED전이및TxAttemptFAILED_TIMEOUT() {
        UUID attemptId = UUID.randomUUID();
        NonceReservation reservation = reservedWithAttempt(attemptId);
        TxAttempt attempt = broadcastedAttempt(attemptId);

        when(nonceReservationRepository.findByStatusAndExpiresAtLessThan(
                eq(NonceReservationStatus.RESERVED), any(Instant.class)))
                .thenReturn(List.of(reservation));
        when(txAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));

        nonceCleaner.clean();

        assertThat(reservation.getStatus()).isEqualTo(NonceReservationStatus.EXPIRED);
        assertThat(attempt.getStatus()).isEqualTo(TxAttemptStatus.FAILED_TIMEOUT);
    }

    @Test
    void clean_attemptId있지만TxAttempt없으면_예약만EXPIRED() {
        UUID attemptId = UUID.randomUUID();
        NonceReservation reservation = reservedWithAttempt(attemptId);

        when(nonceReservationRepository.findByStatusAndExpiresAtLessThan(
                eq(NonceReservationStatus.RESERVED), any(Instant.class)))
                .thenReturn(List.of(reservation));
        when(txAttemptRepository.findById(attemptId)).thenReturn(Optional.empty());

        nonceCleaner.clean();

        assertThat(reservation.getStatus()).isEqualTo(NonceReservationStatus.EXPIRED);
    }

    @Test
    void clean_여러만료예약_모두처리() {
        UUID attemptId1 = UUID.randomUUID();
        UUID attemptId2 = UUID.randomUUID();
        NonceReservation r1 = reservedWithAttempt(attemptId1);
        NonceReservation r2 = reservedWithAttempt(attemptId2);
        TxAttempt a1 = broadcastedAttempt(attemptId1);
        TxAttempt a2 = broadcastedAttempt(attemptId2);

        when(nonceReservationRepository.findByStatusAndExpiresAtLessThan(
                eq(NonceReservationStatus.RESERVED), any(Instant.class)))
                .thenReturn(List.of(r1, r2));
        when(txAttemptRepository.findById(attemptId1)).thenReturn(Optional.of(a1));
        when(txAttemptRepository.findById(attemptId2)).thenReturn(Optional.of(a2));

        nonceCleaner.clean();

        assertThat(r1.getStatus()).isEqualTo(NonceReservationStatus.EXPIRED);
        assertThat(r2.getStatus()).isEqualTo(NonceReservationStatus.EXPIRED);
        assertThat(a1.getStatus()).isEqualTo(TxAttemptStatus.FAILED_TIMEOUT);
        assertThat(a2.getStatus()).isEqualTo(TxAttemptStatus.FAILED_TIMEOUT);
    }

    // ─────────────────────── helpers ───────────────────────

    private NonceReservation reservedWithoutAttempt() {
        return NonceReservation.reserve(
                ChainType.EVM,
                "0xfrom",
                42L,
                UUID.randomUUID(),
                Instant.now().minusSeconds(600));
    }

    private NonceReservation reservedWithAttempt(UUID attemptId) {
        NonceReservation r = reservedWithoutAttempt();
        r.commit(attemptId);
        // commit → COMMITTED, expire()는 RESERVED에서만 가능하므로 상태를 RESERVED로 되돌림
        ReflectionTestUtils.setField(r, "status", NonceReservationStatus.RESERVED);
        ReflectionTestUtils.setField(r, "attemptId", attemptId);
        return r;
    }

    private TxAttempt broadcastedAttempt(UUID id) {
        TxAttempt attempt = TxAttempt.created(UUID.randomUUID(), 1, "0xfrom", 42L, true);
        ReflectionTestUtils.setField(attempt, "id", id);
        attempt.transitionTo(TxAttemptStatus.BROADCASTED);
        return attempt;
    }
}
