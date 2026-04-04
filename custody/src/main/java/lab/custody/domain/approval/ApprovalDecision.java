package lab.custody.domain.approval;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 개별 승인자의 결정 엔티티.
 *
 * <p>한 명의 승인자는 동일 태스크에 대해 한 번만 결정할 수 있다 (DB 유니크 제약).
 */
@Entity
@Table(
    name = "approval_decisions",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_approval_decisions_task_approver",
            columnNames = {"approval_task_id", "approver_id"}
        )
    },
    indexes = {
        @Index(name = "idx_approval_decisions_task_created_at",
               columnList = "approval_task_id, created_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ApprovalDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "approval_task_id", nullable = false, updatable = false)
    private UUID approvalTaskId;

    @Column(name = "approver_id", nullable = false, updatable = false, length = 128)
    private String approverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private ApprovalDecisionType decision;

    /** 결정 사유 (선택) */
    @Column(length = 500)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ─────────────────────────── factory ───────────────────────────

    public static ApprovalDecision of(
            UUID approvalTaskId,
            String approverId,
            ApprovalDecisionType decision,
            String comment) {
        return ApprovalDecision.builder()
                .approvalTaskId(approvalTaskId)
                .approverId(approverId)
                .decision(decision)
                .comment(comment)
                .createdAt(Instant.now())
                .build();
    }
}
