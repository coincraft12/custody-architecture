package lab.custody.domain.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AssetRegistryService 단위 테스트 — Mock Repository 사용.
 */
@ExtendWith(MockitoExtension.class)
class AssetRegistryServiceTest {

    @Mock
    private SupportedAssetRepository assetRepository;

    @Mock
    private SupportedChainRepository chainRepository;

    @InjectMocks
    private AssetRegistryService service;

    private SupportedAsset ethAsset() {
        return SupportedAsset.builder()
                .id(UUID.randomUUID())
                .assetSymbol("ETH")
                .chainType("EVM")
                .contractAddress(null)
                .decimals(18)
                .defaultGasLimit(21000L)
                .enabled(true)
                .native_(true)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void getAsset_whenExists_returnsAsset() {
        SupportedAsset eth = ethAsset();
        when(assetRepository.findByChainTypeAndAssetSymbolAndEnabledTrue("EVM", "ETH"))
                .thenReturn(Optional.of(eth));

        SupportedAsset result = service.getAsset("EVM", "ETH");

        assertThat(result.getAssetSymbol()).isEqualTo("ETH");
        assertThat(result.getChainType()).isEqualTo("EVM");
        assertThat(result.isNative()).isTrue();
    }

    @Test
    void getAsset_whenNotFound_throwsAssetNotSupportedException() {
        when(assetRepository.findByChainTypeAndAssetSymbolAndEnabledTrue("EVM", "UNKNOWN"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAsset("EVM", "UNKNOWN"))
                .isInstanceOf(AssetNotSupportedException.class)
                .hasMessageContaining("UNKNOWN")
                .hasMessageContaining("EVM");
    }

    @Test
    void isAssetSupported_whenExists_returnsTrue() {
        when(assetRepository.findByChainTypeAndAssetSymbolAndEnabledTrue("EVM", "ETH"))
                .thenReturn(Optional.of(ethAsset()));

        assertThat(service.isAssetSupported("EVM", "ETH")).isTrue();
    }

    @Test
    void isAssetSupported_whenNotFound_returnsFalse() {
        when(assetRepository.findByChainTypeAndAssetSymbolAndEnabledTrue("BITCOIN", "XRP"))
                .thenReturn(Optional.empty());

        assertThat(service.isAssetSupported("BITCOIN", "XRP")).isFalse();
    }

    @Test
    void getAssets_returnsEnabledAssetsForChain() {
        SupportedAsset eth = ethAsset();
        when(assetRepository.findByChainTypeAndEnabledTrue("EVM"))
                .thenReturn(List.of(eth));

        List<SupportedAsset> result = service.getAssets("EVM");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAssetSymbol()).isEqualTo("ETH");
    }

    @Test
    void getChains_returnsEnabledChains() {
        SupportedChain evmChain = SupportedChain.builder()
                .chainType("EVM")
                .nativeAsset("ETH")
                .adapterBeanName("evmRpcAdapter")
                .enabled(true)
                .createdAt(Instant.now())
                .build();
        when(chainRepository.findByEnabledTrue()).thenReturn(List.of(evmChain));

        List<SupportedChain> result = service.getChains();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getChainType()).isEqualTo("EVM");
    }
}
