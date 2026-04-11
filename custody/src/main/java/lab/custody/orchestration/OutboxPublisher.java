package lab.custody.orchestration;

import lab.custody.domain.outbox.OutboxEvent;
import lab.custody.domain.outbox.OutboxEventRepository;
import lab.custody.domain.outbox.OutboxEventStatus;
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
 * 6-3-4: Outbox 이벤트 발행 스케줄러.
 *
 * <p>PENDING 상태의 {@link OutboxEvent}를 주기적으로 조회하여 외부 시스템에 발행한다.
 * 현재는 로그 출력으로 대체(Phase 3에서 Kafka/이벤트 버스 연동으로 교체 예정).
 *
 * <p>6-3-5: {@link OutboxEvent#markPublished()}로 {@code PUBLISHED} 처리 → 중복 발행 방지.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;

    @Value("${custody.outbox.max-attempts:3}")
    private int maxAttempts;

    /**
     * PENDING 이벤트 발행 폴링.
     * fixedDelay이므로 이전 실행 완료 후 {@code poll-interval-ms}만큼 대기한 뒤 재실행.
     */
    @Scheduled(fixedDelayString = "${custody.outbox.poll-interval-ms:5000}")
    @Transactional
    public void publish() {
        // 8-1-3: 스케줄러 실행마다 고유 correlationId 생성
        String correlationId = "sched-outbox-" + UUID.randomUUID().toString().substring(0, 8);
        MDC.put("correlationId", correlationId);
        try {
            doPublish();
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void doPublish() {
        List<OutboxEvent> pending = outboxEventRepository
                .findByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAsc(
                        OutboxEventStatus.PENDING, Instant.now());

        if (pending.isEmpty()) {
            return;
        }

        log.debug("event=outbox_publisher.poll scheduler=OutboxPublisher pending_count={}", pending.size());

        for (OutboxEvent event : pending) {
            try {
                // Phase 3: 이 블록을 Kafka producer.send() 또는 이벤트 버스 발행으로 교체한다.
                log.info(
                        "event=outbox_publisher.publish id={} aggregateType={} aggregateId={} eventType={} payload={}",
                        event.getId(),
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getEventType(),
                        event.getPayload());

                // 6-3-5: PUBLISHED 처리 — 중복 발행 방지
                event.markPublished();
                outboxEventRepository.save(event);

            } catch (Exception e) {
                log.warn("event=outbox_publisher.failure id={} eventType={} attemptCount={} error={}",
                        event.getId(), event.getEventType(), event.getAttemptCount(), e.getMessage());
                event.recordFailure(maxAttempts);
                outboxEventRepository.save(event);
            }
        }
    }
}
