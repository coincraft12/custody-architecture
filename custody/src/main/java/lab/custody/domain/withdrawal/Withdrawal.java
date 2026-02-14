package lab.custody.domain.withdrawal;

import jakarta.persistence.*;
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

    public void transitionTo(WithdrawalStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public static Withdrawal requested(String idempotencyKey, String from, String to, String asset, long amount) {
        Instant now = Instant.now();
        return Withdrawal.builder()
                .idempotencyKey(idempotencyKey)
                .fromAddress(from)
                .toAddress(to)
                .asset(asset)
                .amount(amount)
                .status(WithdrawalStatus.W0_REQUESTED)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
