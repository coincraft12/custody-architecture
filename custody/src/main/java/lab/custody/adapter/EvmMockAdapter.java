package lab.custody.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lab.custody.domain.withdrawal.ChainType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Component
public class EvmMockAdapter implements ChainAdapter {

    private static final String SEPOLIA_CHAIN_ID_HEX = "0xaa36a7";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String sepoliaRpcUrl;

    public EvmMockAdapter(
            ObjectMapper objectMapper,
            @Value("${adapter.evm.sepolia-rpc-url:}") String sepoliaRpcUrl
    ) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.sepoliaRpcUrl = sepoliaRpcUrl == null ? "" : sepoliaRpcUrl.trim();
    }

    @Override
    public BroadcastResult broadcast(BroadcastCommand command) {
        if (sepoliaRpcUrl.isBlank()) {
            return new BroadcastResult("0xEVM_MOCK_" + UUID.randomUUID().toString().substring(0, 8), true);
        }

        String chainId = fetchChainId();
        if (!SEPOLIA_CHAIN_ID_HEX.equalsIgnoreCase(chainId)) {
            throw new IllegalStateException("Connected RPC is not Sepolia. expected=" + SEPOLIA_CHAIN_ID_HEX + ", actual=" + chainId);
        }

        String txHash = "0xSEPOLIA_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new BroadcastResult(txHash, true);
    }

    private String fetchChainId() {
        String responseBody = restClient.post()
                .uri(sepoliaRpcUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "method", "eth_chainId",
                        "params", java.util.List.of(),
                        "id", 1
                ))
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode resultNode = root.get("result");
            if (resultNode == null || resultNode.isNull() || resultNode.asText().isBlank()) {
                throw new IllegalStateException("Invalid RPC response: missing result");
            }
            return resultNode.asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse RPC response", e);
        }
    }

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }
}
