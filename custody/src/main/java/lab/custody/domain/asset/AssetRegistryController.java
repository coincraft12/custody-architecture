package lab.custody.domain.asset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chains")
@RequiredArgsConstructor
@Slf4j
public class AssetRegistryController {

    private final AssetRegistryService assetRegistryService;

    /**
     * 활성화된 체인 목록 조회.
     * GET /api/chains
     */
    @GetMapping
    public List<SupportedChainDto> getChains() {
        log.debug("event=asset_registry.get_chains");
        return assetRegistryService.getChains().stream()
                .map(SupportedChainDto::from)
                .toList();
    }

    /**
     * 특정 체인의 활성화된 자산 목록 조회.
     * GET /api/chains/{chainType}/assets
     */
    @GetMapping("/{chainType}/assets")
    public List<SupportedAssetDto> getAssets(@PathVariable String chainType) {
        log.debug("event=asset_registry.get_assets chainType={}", chainType);
        return assetRegistryService.getAssets(chainType).stream()
                .map(SupportedAssetDto::from)
                .toList();
    }
}
