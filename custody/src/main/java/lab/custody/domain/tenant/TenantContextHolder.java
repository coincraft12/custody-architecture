package lab.custody.domain.tenant;

import java.util.UUID;

public class TenantContextHolder {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    public static void set(UUID tenantId) { CURRENT_TENANT.set(tenantId); }

    public static UUID get() { return CURRENT_TENANT.get(); }

    public static UUID getOrDefault() {
        UUID id = CURRENT_TENANT.get();
        return id != null ? id : Tenant.DEFAULT_TENANT_ID;
    }

    public static void clear() { CURRENT_TENANT.remove(); }
}
