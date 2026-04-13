package lab.custody.domain.finality;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 17-9: Repository for {@link FinalityPolicy}.
 */
public interface FinalityPolicyRepository extends JpaRepository<FinalityPolicy, UUID> {

    /**
     * Find the active policy for a given chain type and tier (case-sensitive).
     *
     * @param chainType chain type string (e.g. "EVM", "BITCOIN")
     * @param tier      tier string (e.g. "LOW", "MEDIUM", "HIGH")
     * @return matching policy, or empty if none found / not enabled
     */
    Optional<FinalityPolicy> findByChainTypeAndTierAndEnabledTrue(String chainType, String tier);
}
