package lab.custody.domain.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantMemberRepository extends JpaRepository<TenantMember, UUID> {
    List<TenantMember> findByTenantId(UUID tenantId);
}
