package lab.custody.domain.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox 패턴 이벤트 엔티티.
 *
 * <p>WithdrawalService 등 주요 상태 전이 시, DB 트랜잭션 내에서 이벤트를 함께 기록한다.
 * OutboxPublisher 스케줄러가 PENDING 이벤트를 조회해 외부 시스템(Kafka 등)에 발행한다.
 *
 * <p>중복 발행 방지: status = PUBLISHED 처리로 재발행을 차단한다.
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_events_status_available_at",
               columnList = "status, available_at"),
        @Index(name = "idx_outbox_events_aggregate",
               columnList = "aggregate_type, aggregate_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 집합체 타입 — 예: "Withdrawal", "WhitelistAddress" */
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    /** 집합체 식별자 */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** 이벤트 종류 — 예: "WITHDRAWAL_BROADCASTED", "WHITELIST_APPROVED" */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** JSON 직렬화된 이벤트 페이로드 */
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private OutboxEventStatus status = OutboxEventStatus.PENDING;

    /** OutboxPublisher가 재시도한 횟수 */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    /** 이 시각 이후부터 발행 가능 (지연 발행 지원) */
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 실제 발행 완료 시각 */
    @Column(name = "sent_at")
    private Instant sentAt;

    // ─────────────────────────── factory ───────────────────────────

    public static OutboxEvent create(
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload) {
        Instant now = Instant.now();
        return OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .availableAt(now)
                .createdAt(now)
                .build();
    }

    // ─────────────────────────── state transitions ───────────────────────────

    /** 발행 성공: PENDING → PUBLISHED */
    public void markPublished() {
        this.status = OutboxEventStatus.PUBLISHED;
        this.sentAt = Instant.now();
        this.attemptCount++;
    }

    /** 발행 실패 후 재시도 카운트 증가, 최대 초과 시 FAILED 처리 */
    public void recordFailure(int maxAttempts) {
        this.attemptCount++;
        if (this.attemptCount >= maxAttempts) {
            this.status = OutboxEventStatus.FAILED;
        }
    }
}
