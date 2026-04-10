package lab.custody.orchestration;

import lab.custody.domain.nonce.NonceReservation;
import lab.custody.domain.nonce.NonceReservationRepository;
import lab.custody.domain.nonce.NonceReservationStatus;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 만료된 넌스 예약을 주기적으로 정리하는 스케줄러.
 *
 * <p>RESERVED 상태에서 {@code custody.nonce.expiry-minutes} 이상 경과한 예약을
 * EXPIRED로 전이하고, 해당 TxAttempt를 FAILED_TIMEOUT으로 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NonceCleaner {

    private final NonceReservationRepository nonceReservationRepository;
    private final TxAttemptRepository txAttemptRepository;

    @Value("${custody.nonce.expiry-minutes:10}")
    private int expiryMinutes;

    /**
     * 매 1분마다 만료된 넌스 예약을 정리한다.
     */
    @Scheduled(fixedDelayString = "${custody.nonce.cleaner-delay-ms:60000}")
    @Transactional
    public void clean() {
        Instant now = Instant.now();

        List<NonceReservation> expired = nonceReservationRepository
                .findByStatusAndExpiresAtLessThan(NonceReservationStatus.RESERVED, now);

        if (expired.isEmpty()) {
            return;
        }

        log.info("[NonceCleaner] 만료 예약 {}건 처리 시작", expired.size());

        for (NonceReservation reservation : expired) {
            reservation.expire();

            if (reservation.getAttemptId() != null) {
                txAttemptRepository.findById(reservation.getAttemptId())
                        .ifPresent(attempt -> {
                            attempt.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
                            log.info("[NonceCleaner] TxAttempt {} → FAILED_TIMEOUT (nonce={})",
                                    attempt.getId(), reservation.getNonce());
                        });
            }
        }

        log.info("[NonceCleaner] 만료 처리 완료: {}건", expired.size());
    }
}
