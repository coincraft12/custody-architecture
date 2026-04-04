package lab.custody.domain.approval;

public enum ApprovalTaskStatus {
    /** 승인 대기 중 */
    PENDING,
    /** 필요 승인 수 충족 — 출금 재개 가능 */
    APPROVED,
    /** 거부됨 — 출금 W0_POLICY_REJECTED 전이 */
    REJECTED,
    /** 만료 시각 초과 */
    EXPIRED
}
