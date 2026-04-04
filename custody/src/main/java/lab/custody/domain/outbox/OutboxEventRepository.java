package lab.custody.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** OutboxPublisher: 발행 가능한 PENDING 이벤트 조회 (available_at 기준) */
    List<OutboxEvent> findByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAsc(
            OutboxEventStatus status, Instant now);

    /** 특정 집합체의 이벤트 이력 조회 */
    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType, UUID aggregateId);

    long countByStatus(OutboxEventStatus status);

    /** 특정 이벤트 타입의 미발행 건수 확인 */
    @Query("""
            SELECT COUNT(e) FROM OutboxEvent e
             WHERE e.eventType = :eventType
               AND e.status = lab.custody.domain.outbox.OutboxEventStatus.PENDING
            """)
    long countPendingByEventType(@Param("eventType") String eventType);
}
