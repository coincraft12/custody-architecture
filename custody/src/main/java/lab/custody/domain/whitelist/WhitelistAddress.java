package lab.custody.domain.whitelist;

import jakarta.persistence.*;
import lab.custody.domain.tenant.TenantContextHolder;
import lab.custody.domain.withdrawal.ChainType;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 출금 허용 주소 화이트리스트 엔티티.
 *
 * 상태머신: REGISTERED → HOLDING → ACTIVE (취소 시 REVOKED)
 * - approve() 호출 시 holdDurationHours 만큼 보류 (기본 48h)
 * - 스케줄러가 activeAfter 경과 여부를 확인해 ACTIVE 로 전환
 */
@Entity
@Table(name = "whitelist_addresses",
        indexes = {
                @Index(name = "idx_whitelist_addr_chain", columnList = "address, chainType", unique = true),
                @Index(name = "idx_whitelist_status", columnList = "status"),
                @Index(name = "idx_whitelist_active_after", columnList = "activeAfter")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WhitelistAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 체인 주소 (EVM: 0x... 형식) */
    @Column(nullable = false, length = 64)
    private String address;

    /** 체인 종류 — (address, chainType) 복합 유니크 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChainType chainType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WhitelistStatus status;

    /** 등록 요청자 식별자 (userId 등) */
    @Column(nullable = false, length = 64)
    private String registeredBy;

    /** 승인자 식별자 (approve 시 기록) */
    @Column(length = 64)
    private String approvedBy;

    /** 취소자 식별자 (revoke 시 기록) */
    @Column(length = 64)
    private String revokedBy;

    /** 등록 사유 메모 */
    @Column(length = 255)
    private String note;

    /**
     * 보류 시간(시간 단위). approve() 시 activeAfter 계산에 사용.
     * 기본값 48 → 48h 후 ACTIVE 가능.
     */
    @Column(nullable = false)
    private long holdDurationHours;

    @Column(nullable = false, updatable = false)
    private Instant registeredAt;

    /** approve() 호출 시각 */
    @Column
    private Instant approvedAt;

    /**
     * approvedAt + holdDurationHours 시각.
     * 스케줄러가 now >= activeAfter 이면 ACTIVE 로 전환.
     */
    @Column
    private Instant activeAfter;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(name = "tenant_id")
    private UUID tenantId;

    // ─────────────────────────── factory ───────────────────────────

    public static WhitelistAddress register(
            String address,
            ChainType chainType,
            String registeredBy,
            String note,
            long holdDurationHours) {
        Instant now = Instant.now();
        return WhitelistAddress.builder()
                .address(address)
                .chainType(chainType)
                .status(WhitelistStatus.REGISTERED)
                .registeredBy(registeredBy)
                .note(note)
                .holdDurationHours(holdDurationHours)
                .tenantId(TenantContextHolder.getOrDefault())
                .registeredAt(now)
                .updatedAt(now)
                .build();
    }

    // ─────────────────────────── state transitions ───────────────────────────

    /**
     * 관리자 승인: REGISTERED → HOLDING.
     * activeAfter = 승인 시각 + holdDurationHours.
     */
    public void approve(String approvedBy) {
        if (this.status != WhitelistStatus.REGISTERED) {
            throw new IllegalStateException(
                    "approve() 는 REGISTERED 상태에서만 가능합니다. 현재: " + this.status);
        }
        Instant now = Instant.now();
        this.approvedBy = approvedBy;
        this.approvedAt = now;
        this.activeAfter = now.plusSeconds(holdDurationHours * 3600);
        this.status = WhitelistStatus.HOLDING;
        this.updatedAt = now;
    }

    /**
     * 스케줄러 호출: HOLDING → ACTIVE.
     * activeAfter 경과 여부는 호출자(스케줄러)가 사전에 확인.
     */
    public void activate() {
        if (this.status != WhitelistStatus.HOLDING) {
            throw new IllegalStateException(
                    "activate() 는 HOLDING 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = WhitelistStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /**
     * 취소: any → REVOKED.
     * REVOKED 상태에서 재취소는 허용하지 않음.
     */
    public void revoke(String revokedBy) {
        if (this.status == WhitelistStatus.REVOKED) {
            throw new IllegalStateException("이미 REVOKED 상태입니다.");
        }
        this.revokedBy = revokedBy;
        this.status = WhitelistStatus.REVOKED;
        this.updatedAt = Instant.now();
    }

    /** 정책 엔진에서 사용 가능 여부 확인 */
    public boolean isActive() {
        return this.status == WhitelistStatus.ACTIVE;
    }
}
