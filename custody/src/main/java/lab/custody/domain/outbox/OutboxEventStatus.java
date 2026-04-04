package lab.custody.domain.outbox;

public enum OutboxEventStatus {
    /** 발행 대기 중 */
    PENDING,
    /** 발행 완료 */
    PUBLISHED,
    /** 재시도 소진 후 최종 실패 */
    FAILED
}
