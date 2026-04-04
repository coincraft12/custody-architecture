package lab.custody.domain.approval;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 출금 승인 태스크 엔티티.
 *
 * <p>고금액·고위험 출금 요청 시 W2_APPROVAL_PENDING 상태에서 생성된다.
 * 4-eyes 정책(2인 이상 승인)을 지원하며, approvedCount가 requiredApprovals에
 * 도달하면 APPROVED로 전이한다.
 *
 * <p>상태 전이: PENDING → APPROVED (승인 수 충족)
 *             PENDING → REJECTED (거부 결정)
 *             PENDING → EXPIRED  (만료 스케줄러)
 */
@Entity
@Table(
    name = "approval_tasks",
    indexes = {
        @Index(name = "idx_approval_tasks_withdrawal_id",      columnList = "withdrawal_id"),
        @Index(name = "idx_approval_tasks_status_expires_at",  columnList = "status, expires_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ApprovalTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "withdrawal_id", nullable = false, updatable = false)
    private UUID withdrawalId;

    /** 위험 등급 — 예: "LOW", "HIGH", "CRITICAL" */
    @Column(name = "risk_tier", nullable = false, length = 16)
    private String riskTier;

    /** 이 태스크를 APPROVED로 전환하기 위해 필요한 최소 승인 수 */
    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals;

    /** 현재까지 누적된 APPROVED 결정 수 */
    @Column(name = "approved_count", nullable = false)
    @Builder.Default
    private int approvedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ApprovalTaskStatus status = ApprovalTaskStatus.PENDING;

    /** 태스크 만료 시각 — 초과 시 EXPIRED 처리 */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ─────────────────────────── factory ───────────────────────────

    public static ApprovalTask create(
            UUID withdrawalId,
            String riskTier,
            int requiredApprovals,
            Instant expiresAt) {
        Instant now = Instant.now();
        return ApprovalTask.builder()
                .withdrawalId(withdrawalId)
                .riskTier(riskTier)
                .requiredApprovals(requiredApprovals)
                .approvedCount(0)
                .status(ApprovalTaskStatus.PENDING)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // ─────────────────────────── state transitions ───────────────────────────

    /**
     * 개별 승인 결정 반영. approvedCount가 requiredApprovals에 도달하면 APPROVED 전이.
     *
     * @return true if the task reached APPROVED status as a result of this call
     */
    public boolean recordApproval() {
        if (this.status != ApprovalTaskStatus.PENDING) {
            throw new IllegalStateException(
                "recordApproval() 는 PENDING 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.approvedCount++;
        this.updatedAt = Instant.now();
        if (this.approvedCount >= this.requiredApprovals) {
            this.status = ApprovalTaskStatus.APPROVED;
            return true;
        }
        return false;
    }

    /** 거부 결정: PENDING → REJECTED */
    public void reject() {
        if (this.status != ApprovalTaskStatus.PENDING) {
            throw new IllegalStateException(
                "reject() 는 PENDING 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = ApprovalTaskStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    /** 만료 처리: PENDING → EXPIRED */
    public void expire() {
        if (this.status != ApprovalTaskStatus.PENDING) {
            throw new IllegalStateException(
                "expire() 는 PENDING 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = ApprovalTaskStatus.EXPIRED;
        this.updatedAt = Instant.now();
    }
}
