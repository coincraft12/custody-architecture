package lab.custody.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportedAssetRepository extends JpaRepository<SupportedAsset, UUID> {

    Optional<SupportedAsset> findByChainTypeAndAssetSymbolAndEnabledTrue(String chainType, String assetSymbol);

    List<SupportedAsset> findByChainTypeAndEnabledTrue(String chainType);
}
