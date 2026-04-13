package lab.custody.domain.asset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AssetRegistryController @WebMvcTest — 서비스를 Mock으로 교체해 HTTP 레이어만 검증.
 * 보안 필터는 테스트 properties에서 비활성화.
 */
@WebMvcTest(AssetRegistryController.class)
@TestPropertySource(properties = {
        "custody.security.enabled=false",
        "custody.rate-limit.enabled=false",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration," +
                "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
})
class AssetRegistryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssetRegistryService assetRegistryService;

    @Test
    void getChains_returnsChainList() throws Exception {
        SupportedChain evmChain = SupportedChain.builder()
                .chainType("EVM")
                .nativeAsset("ETH")
                .adapterBeanName("evmRpcAdapter")
                .enabled(true)
                .createdAt(Instant.now())
                .build();
        when(assetRegistryService.getChains()).thenReturn(List.of(evmChain));

        mockMvc.perform(get("/api/chains").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].chainType").value("EVM"))
                .andExpect(jsonPath("$[0].nativeAsset").value("ETH"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void getAssets_returnsAssetListForChain() throws Exception {
        SupportedAsset eth = SupportedAsset.builder()
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
        when(assetRegistryService.getAssets("EVM")).thenReturn(List.of(eth));

        mockMvc.perform(get("/api/chains/EVM/assets").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].assetSymbol").value("ETH"))
                .andExpect(jsonPath("$[0].chainType").value("EVM"))
                .andExpect(jsonPath("$[0].decimals").value(18))
                .andExpect(jsonPath("$[0].isNative").value(true));
    }

    @Test
    void getAssets_whenNoAssets_returnsEmptyList() throws Exception {
        when(assetRegistryService.getAssets("SOLANA")).thenReturn(List.of());

        mockMvc.perform(get("/api/chains/SOLANA/assets").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
