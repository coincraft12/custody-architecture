package lab.custody.domain.nonce;

public enum NonceReservationStatus {
    /** 넌스 예약됨 — 아직 트랜잭션 브로드캐스트 전 */
    RESERVED,
    /** 트랜잭션 브로드캐스트 완료 후 커밋 */
    COMMITTED,
    /** 정상 완료 또는 retry/replace 후 해제 */
    RELEASED,
    /** 만료 시간 초과로 정리됨 */
    EXPIRED
}
