package lab.custody.domain.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantApiKeyRepository extends JpaRepository<TenantApiKey, UUID> {
    Optional<TenantApiKey> findByKeyHashAndEnabledTrue(String keyHash);
}
