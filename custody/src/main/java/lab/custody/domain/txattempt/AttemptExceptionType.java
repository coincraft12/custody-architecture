package lab.custody.domain.txattempt;

/**
 * TxAttempt 예외 분류.
 *
 * <p>4-2-5: RPC 오류 분류 체계
 * <ul>
 *   <li>{@link #RPC_TRANSIENT} — 일시적 오류 (timeout, rate-limit 429, 일시 네트워크 불안정)</li>
 *   <li>{@link #RPC_PERMANENT} — 영구 오류 (invalid tx, insufficient funds, invalid nonce)</li>
 *   <li>{@link #RPC_NETWORK} — 네트워크 오류 (connection refused, circuit breaker open)</li>
 *   <li>{@link #RPC_INCONSISTENT} — nonce 불일치 등 상태 정합성 오류 (자동 재예약 트리거)</li>
 * </ul>
 */
public enum AttemptExceptionType {
    /** 일반 시스템 오류 — retry 소진 후 최종 실패 */
    FAILED_SYSTEM,

    /** 넌스 예약 만료 */
    EXPIRED,

    /** TX가 mempool에서 드롭됨 */
    DROPPED,

    /** 동일 nonce로 다른 TX가 채굴됨 (replaced) */
    REPLACED,

    /** TX 채굴되었으나 revert 발생 */
    REVERTED,

    /** 4-2-5: nonce 불일치 등 RPC 상태 정합성 오류 → 자동 재예약 복구 트리거 */
    RPC_INCONSISTENT,

    /** 4-2-5: 일시적 RPC 오류 — timeout, rate-limit 429 → Retry 대상 */
    RPC_TRANSIENT,

    /** 4-2-5: 영구적 RPC 오류 — invalid tx, insufficient funds → 재시도 불가 */
    RPC_PERMANENT,

    /** 4-2-5: 네트워크 수준 오류 — connection refused, circuit breaker open → 인프라 점검 필요 */
    RPC_NETWORK
}
