package lab.custody.domain.tenant;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 64)
    private String plan;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static final UUID DEFAULT_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static Tenant createDefault() {
        Tenant t = new Tenant();
        t.tenantId = DEFAULT_TENANT_ID;
        t.name = "DEFAULT";
        t.status = "ACTIVE";
        t.createdAt = Instant.now();
        return t;
    }
}
