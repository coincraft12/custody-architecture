package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.nonce.NonceReservation;
import lab.custody.domain.nonce.NonceReservationRepository;
import lab.custody.domain.withdrawal.ChainType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NonceAllocator {

    private static final int MAX_RESERVATION_ATTEMPTS = 100;

    private final NonceReservationRepository nonceReservationRepository;
    private final ChainAdapterRouter router;

    @Transactional
    public NonceReservation reserve(ChainType chainType, String fromAddress, UUID withdrawalId) {
        String normalizedFromAddress = normalizeAddress(fromAddress);
        long candidate = initialCandidate(chainType, normalizedFromAddress);

        for (int i = 0; i < MAX_RESERVATION_ATTEMPTS; i++) {
            NonceReservation reservation = NonceReservation.reserve(
                    chainType,
                    normalizedFromAddress,
                    candidate,
                    withdrawalId,
                    null
            );
            try {
                return nonceReservationRepository.save(reservation);
            } catch (DataIntegrityViolationException e) {
                candidate++;
            }
        }

        throw new IllegalStateException("failed to reserve nonce after " + MAX_RESERVATION_ATTEMPTS + " attempts");
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

    private long initialCandidate(ChainType chainType, String fromAddress) {
        long rpcPendingNonce = readPendingNonce(chainType, fromAddress);
        long nextAfterActive = nonceReservationRepository.findMaxActiveNonce(chainType, fromAddress)
                .map(maxActive -> maxActive + 1)
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
