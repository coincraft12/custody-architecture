package lab.custody.domain.withdrawal;

import jakarta.persistence.*;
import lab.custody.domain.tenant.TenantContextHolder;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "withdrawals",
       indexes = {
           @Index(name = "idx_withdrawal_idem", columnList = "idempotencyKey", unique = true)
       })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Withdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String fromAddress;

    @Column(nullable = false, length = 64)
    private String toAddress;

    @Column(nullable = false, length = 32)
    private String asset;

    @Column(nullable = false)
    private long amount; // 단순화: smallest unit

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WithdrawalStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChainType chainType;

    @Column(name = "tenant_id")
    private UUID tenantId;

    public void transitionTo(WithdrawalStatus next) {
        // W0_POLICY_REJECTED는 어떤 상태에서든 전이 가능 (승인 거부, 만료 등)
        if (next != WithdrawalStatus.W0_POLICY_REJECTED
                && next.ordinal() < this.status.ordinal()) {
            throw new IllegalStateException("invalid withdrawal status transition: " + this.status + " -> " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public static Withdrawal requested(
        String idempotencyKey, 
        ChainType chainType, 
        String from, 
        String to, 
        String asset, 
        long amount) {
        Instant now = Instant.now();
        return Withdrawal.builder()
                .idempotencyKey(idempotencyKey)
                .chainType(chainType)
                .fromAddress(from)
                .toAddress(to)
                .asset(asset)
                .amount(amount)
                .status(WithdrawalStatus.W0_REQUESTED)
                .tenantId(TenantContextHolder.getOrDefault())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
