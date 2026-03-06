package lab.custody.domain.whitelist;

/**
 * 화이트리스트 주소 상태머신
 *
 * REGISTERED → HOLDING  (approve() 호출 시, 48h 보류 타이머 시작)
 * HOLDING    → ACTIVE   (activeAfter 경과 후 스케줄러가 전환)
 * any        → REVOKED  (revoke() 호출 시)
 */
public enum WhitelistStatus {
    /** 등록 요청됨, 아직 승인 대기 */
    REGISTERED,
    /** 승인됨, 48h 보류 카운트다운 진행 중 */
    HOLDING,
    /** 보류 완료, 출금 대상 주소로 사용 가능 */
    ACTIVE,
    /** 취소됨, 더 이상 사용 불가 */
    REVOKED
}
