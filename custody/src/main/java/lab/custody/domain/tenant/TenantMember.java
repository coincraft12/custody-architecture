package lab.custody.domain.tenant;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_members",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_tenant_members_tenant_user",
        columnNames = {"tenant_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(nullable = false, length = 32)
    private String role;  // OPERATOR, APPROVER, ADMIN, AUDITOR

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
