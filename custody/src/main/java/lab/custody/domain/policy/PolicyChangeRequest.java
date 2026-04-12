package lab.custody.domain.policy;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 정책 규칙 변경 요청 감사 로그 엔티티.
 *
 * <p>PolicyEngine 규칙이 변경될 때 불변 감사 레코드를 기록한다.
 * 대응 테이블: policy_change_requests (V1 마이그레이션에서 생성됨)
 */
@Entity
@Table(name = "policy_change_requests", indexes = {
        @Index(name = "idx_policy_change_requests_status_apply_after",
               columnList = "status, apply_after")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PolicyChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * 변경 유형 — 예: "AMOUNT_LIMIT_CHANGED", "WHITELIST_RULE_CHANGED",
     * "POLICY_RULE_SNAPSHOT"
     */
    @Column(name = "change_type", nullable = false, length = 64)
    private String changeType;

    /**
     * 처리 상태 — "APPLIED" (감사 목적 즉시 기록 시),
     * "PENDING" (승인 대기), "REJECTED"
     */
    @Column(nullable = false, length = 32)
    private String status;

    /**
     * 변경 내용 JSON 페이로드 (규칙 이름, 이전/이후 설정값 등).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** 변경을 요청한 주체 (userId, "system:startup" 등) */
    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    /** 승인 일시 (즉시 적용 시 createdAt과 동일하게 설정) */
    @Column(name = "approved_at")
    private Instant approvedAt;

    /** 적용 예약 일시 (null이면 즉시 적용) */
    @Column(name = "apply_after")
    private Instant applyAfter;

    /** 실제 적용 완료 일시 */
    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ─────────────────────────── factory ───────────────────────────

    /**
     * 즉시 적용된 정책 변경 감사 레코드를 생성한다.
     *
     * @param changeType 변경 유형 (예: "AMOUNT_LIMIT_CHANGED")
     * @param payload    변경 내용 JSON 문자열
     * @param requestedBy 변경 요청자
     * @return 즉시 적용(APPLIED) 상태의 변경 요청 레코드
     */
    public static PolicyChangeRequest applied(String changeType, String payload, String requestedBy) {
        Instant now = Instant.now();
        return PolicyChangeRequest.builder()
                .changeType(changeType)
                .status("APPLIED")
                .payload(payload)
                .requestedBy(requestedBy)
                .approvedAt(now)
                .appliedAt(now)
                .createdAt(now)
                .build();
    }
}
