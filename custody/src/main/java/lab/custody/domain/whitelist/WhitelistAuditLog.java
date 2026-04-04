package lab.custody.domain.whitelist;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 화이트리스트 변경 감사 로그 엔티티.
 *
 * <p>WhitelistService의 approve(), revoke(), activate() 호출 시
 * 상태 변경 이력을 불변 레코드로 기록한다.
 *
 * <p>대응 테이블: whitelist_audit_log (V3 마이그레이션에서 생성)
 */
@Entity
@Table(
    name = "whitelist_audit_log",
    indexes = {
        @Index(name = "idx_whitelist_audit_log_address_created_at",
               columnList = "whitelist_address_id, created_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WhitelistAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 변경 대상 WhitelistAddress ID */
    @Column(name = "whitelist_address_id", nullable = false, updatable = false)
    private UUID whitelistAddressId;

    /**
     * 수행된 액션 — 예: "APPROVED", "REVOKED", "ACTIVATED"
     * WhitelistAddress 상태 전이 메서드명과 일치시킴.
     */
    @Column(nullable = false, updatable = false, length = 32)
    private String action;

    /** 액션을 수행한 주체 식별자 (userId 등) */
    @Column(name = "actor_id", nullable = false, updatable = false, length = 128)
    private String actorId;

    /** 변경 전 상태 (최초 등록 시 null 가능) */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 32)
    private WhitelistStatus previousStatus;

    /** 변경 후 상태 */
    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 32)
    private WhitelistStatus newStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ─────────────────────────── factory ───────────────────────────

    public static WhitelistAuditLog record(
            UUID whitelistAddressId,
            String action,
            String actorId,
            WhitelistStatus previousStatus,
            WhitelistStatus newStatus) {
        return WhitelistAuditLog.builder()
                .whitelistAddressId(whitelistAddressId)
                .action(action)
                .actorId(actorId)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .createdAt(Instant.now())
                .build();
    }
}
