package lab.custody.orchestration;

import lab.custody.domain.nonce.NonceReservation;
import lab.custody.domain.nonce.NonceReservationRepository;
import lab.custody.domain.nonce.NonceReservationStatus;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        // 8-1-3: 스케줄러 실행마다 고유 correlationId 생성하여 MDC에 등록
        String correlationId = "sched-nonce-" + UUID.randomUUID().toString().substring(0, 8);
        MDC.put("correlationId", correlationId);
        try {
            Instant now = Instant.now();

            List<NonceReservation> expired = nonceReservationRepository
                    .findByStatusAndExpiresAtLessThan(NonceReservationStatus.RESERVED, now);

            if (expired.isEmpty()) {
                return;
            }

            // 8-1-4: scheduler=NonceCleaner 형식 구조화 로그
            log.info("event=nonce_cleaner.start scheduler=NonceCleaner expired_count={}", expired.size());

            for (NonceReservation reservation : expired) {
                reservation.expire();

                if (reservation.getAttemptId() != null) {
                    txAttemptRepository.findById(reservation.getAttemptId())
                            .ifPresent(attempt -> {
                                attempt.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
                                log.info("event=nonce_cleaner.attempt_expired scheduler=NonceCleaner attemptId={} nonce={}",
                                        attempt.getId(), reservation.getNonce());
                            });
                }
            }

            log.info("event=nonce_cleaner.done scheduler=NonceCleaner expired_count={}", expired.size());
        } finally {
            MDC.remove("correlationId");
        }
    }
}
