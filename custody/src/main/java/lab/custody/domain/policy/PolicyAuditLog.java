package lab.custody.domain.policy;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_audit_logs", indexes = {
        @Index(name = "idx_policy_audit_withdrawal", columnList = "withdrawalId")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PolicyAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID withdrawalId;

    @Column(nullable = false)
    private boolean allowed;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static PolicyAuditLog of(UUID withdrawalId, boolean allowed, String reason) {
        return PolicyAuditLog.builder()
                .withdrawalId(withdrawalId)
                .allowed(allowed)
                .reason(reason)
                .createdAt(Instant.now())
                .build();
    }
}
