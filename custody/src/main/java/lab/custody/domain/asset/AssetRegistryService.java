package lab.custody.domain.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssetRegistryService {

    private final SupportedAssetRepository assetRepository;
    private final SupportedChainRepository chainRepository;

    /**
     * 특정 체인+심볼의 활성화된 자산을 조회한다.
     * 미지원 또는 disabled 자산이면 AssetNotSupportedException을 던진다.
     */
    public SupportedAsset getAsset(String chainType, String assetSymbol) {
        return assetRepository.findByChainTypeAndAssetSymbolAndEnabledTrue(chainType, assetSymbol)
                .orElseThrow(() -> new AssetNotSupportedException(chainType, assetSymbol));
    }

    /**
     * 특정 체인+심볼 자산의 지원 여부를 반환한다 (enabled=true 기준).
     */
    public boolean isAssetSupported(String chainType, String assetSymbol) {
        return assetRepository.findByChainTypeAndAssetSymbolAndEnabledTrue(chainType, assetSymbol).isPresent();
    }

    /**
     * 특정 체인의 활성화된 자산 목록을 반환한다.
     */
    public List<SupportedAsset> getAssets(String chainType) {
        return assetRepository.findByChainTypeAndEnabledTrue(chainType);
    }

    /**
     * 활성화된 체인 목록을 반환한다.
     */
    public List<SupportedChain> getChains() {
        return chainRepository.findByEnabledTrue();
    }
}
