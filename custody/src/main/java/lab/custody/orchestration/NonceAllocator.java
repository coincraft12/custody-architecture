package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.nonce.NonceReservation;
import lab.custody.domain.nonce.NonceReservationRepository;
import lab.custody.domain.withdrawal.ChainType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NonceAllocator {

    private final NonceReservationRepository nonceReservationRepository;
    private final ChainAdapterRouter router;

    @Value("${custody.nonce.expiry-minutes:10}")
    private int expiryMinutes;

    /**
     * 넌스를 예약한다.
     *
     * <p>동시 예약 충돌 방지: {@code findActiveWithLock}으로 같은 주소의 활성 예약을
     * SELECT FOR UPDATE로 잠근 뒤 최대 넌스를 계산하므로, 동일 주소에 대한 병렬 예약이
     * 트랜잭션 직렬화되어 중복 넌스 발급이 발생하지 않는다.
     */
    @Transactional
    public NonceReservation reserve(ChainType chainType, String fromAddress, UUID withdrawalId) {
        String normalizedFromAddress = normalizeAddress(fromAddress);

        // SELECT FOR UPDATE — 동일 주소 활성 예약 레코드 잠금
        List<NonceReservation> active = nonceReservationRepository
                .findActiveWithLock(chainType, normalizedFromAddress);

        long candidate = candidateFromActive(active, chainType, normalizedFromAddress);
        Instant expiresAt = Instant.now().plusSeconds((long) expiryMinutes * 60);

        NonceReservation reservation = NonceReservation.reserve(
                chainType,
                normalizedFromAddress,
                candidate,
                withdrawalId,
                expiresAt
        );
        return nonceReservationRepository.save(reservation);
    }

    @Transactional
    public NonceReservation commit(UUID reservationId, UUID attemptId) {
        NonceReservation reservation = load(reservationId);
        reservation.commit(attemptId);
        return nonceReservationRepository.save(reservation);
    }

    @Transactional
    public NonceReservation release(UUID reservationId) {
        NonceReservation reservation = load(reservationId);
        reservation.release();
        return nonceReservationRepository.save(reservation);
    }

    @Transactional
    public Optional<NonceReservation> releaseByAttemptIdIfPresent(UUID attemptId) {
        return nonceReservationRepository.findByAttemptId(attemptId).map(reservation -> {
            reservation.release();
            return nonceReservationRepository.save(reservation);
        });
    }

    @Transactional
    public Optional<NonceReservation> rebindAttemptIfPresent(UUID currentAttemptId, UUID newAttemptId) {
        return nonceReservationRepository.findByAttemptId(currentAttemptId).map(reservation -> {
            reservation.rebindAttempt(newAttemptId);
            return nonceReservationRepository.save(reservation);
        });
    }

    private NonceReservation load(UUID reservationId) {
        return nonceReservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("nonce reservation not found: " + reservationId));
    }

    private long candidateFromActive(List<NonceReservation> active, ChainType chainType, String fromAddress) {
        long rpcPendingNonce = readPendingNonce(chainType, fromAddress);
        long nextAfterActive = active.stream()
                .mapToLong(NonceReservation::getNonce)
                .max()
                .stream()
                .mapToObj(max -> max + 1)
                .findFirst()
                .orElse(rpcPendingNonce);
        return Math.max(rpcPendingNonce, nextAfterActive);
    }

    private long readPendingNonce(ChainType chainType, String fromAddress) {
        ChainAdapter adapter = router.resolve(chainType);
        if (adapter instanceof EvmRpcAdapter rpcAdapter) {
            return rpcAdapter.getPendingNonce(fromAddress).longValue();
        }
        return 0L;
    }

    private String normalizeAddress(String fromAddress) {
        if (fromAddress == null) {
            throw new InvalidRequestException("fromAddress is required");
        }
        String normalized = fromAddress.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new InvalidRequestException("fromAddress is required");
        }
        return normalized;
    }
}
