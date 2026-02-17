package lab.custody.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lab.custody.domain.withdrawal.ChainType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class EvmMockAdapter implements ChainAdapter {

    private static final String SEPOLIA_CHAIN_ID_HEX = "0xaa36a7";
    private static final BigInteger SEPOLIA_CHAIN_ID_DECIMAL = BigInteger.valueOf(11155111L);
    private static final BigInteger DEFAULT_GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final Pattern EVM_ADDRESS_PATTERN = Pattern.compile("^0x[a-fA-F0-9]{40}$");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String sepoliaRpcUrl;
    private final String senderPrivateKey;

    public EvmMockAdapter(
            ObjectMapper objectMapper,
            @Value("${adapter.evm.sepolia-rpc-url:}") String sepoliaRpcUrl,
            @Value("${adapter.evm.sender-private-key:}") String senderPrivateKey
    ) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.sepoliaRpcUrl = sepoliaRpcUrl == null ? "" : sepoliaRpcUrl.trim();
        this.senderPrivateKey = senderPrivateKey == null ? "" : senderPrivateKey.trim();
    }

    @Override
    public BroadcastResult broadcast(BroadcastCommand command) {
        if (sepoliaRpcUrl.isBlank()) {
            return new BroadcastResult("0xEVM_MOCK_" + UUID.randomUUID().toString().substring(0, 8), true);
        }

        String chainId = rpcCall("eth_chainId", List.of()).asText();
        if (!SEPOLIA_CHAIN_ID_HEX.equalsIgnoreCase(chainId)) {
            throw new IllegalStateException("Connected RPC is not Sepolia. expected=" + SEPOLIA_CHAIN_ID_HEX + ", actual=" + chainId);
        }

        if (senderPrivateKey.isBlank()) {
            throw new IllegalStateException("adapter.evm.sender-private-key is required to send a real Sepolia transaction");
        }
        if (!isValidAddress(command.to())) {
            throw new IllegalArgumentException("Invalid EVM to-address: " + command.to());
        }

        Credentials credentials = Credentials.create(senderPrivateKey);
        String fromAddress = credentials.getAddress();

        BigInteger nonce = hexToBigInteger(rpcCall(
                "eth_getTransactionCount",
                List.of(fromAddress, "pending")
        ).asText());

        BigInteger gasPrice = hexToBigInteger(rpcCall("eth_gasPrice", List.of()).asText());
        BigInteger valueWei = BigInteger.valueOf(command.amount());

        RawTransaction rawTransaction = RawTransaction.createEtherTransaction(
                nonce,
                gasPrice,
                DEFAULT_GAS_LIMIT,
                command.to(),
                valueWei
        );

        byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, SEPOLIA_CHAIN_ID_DECIMAL.longValue(), credentials);
        String signedTxHex = Numeric.toHexString(signedMessage);

        String txHash = rpcCall("eth_sendRawTransaction", List.of(signedTxHex)).asText();
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalStateException("Sepolia RPC returned empty tx hash");
        }

        String txHash = "0xSEPOLIA_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new BroadcastResult(txHash, true);
    }

    private JsonNode rpcCall(String method, List<?> params) {
        String responseBody = restClient.post()
                .uri(sepoliaRpcUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "method", method,
                        "params", params,
                        "id", 1
                ))
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errorNode = root.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                throw new IllegalStateException("RPC error for " + method + ": " + errorNode.toString());
            }

            JsonNode resultNode = root.get("result");
            if (resultNode == null || resultNode.isNull() || resultNode.asText().isBlank()) {
                throw new IllegalStateException("Invalid RPC response for " + method + ": missing result");
            }
            return resultNode;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse RPC response for " + method, e);
        }
    }

    private static boolean isValidAddress(String address) {
        return address != null && EVM_ADDRESS_PATTERN.matcher(address).matches();
    }

    private static BigInteger hexToBigInteger(String hexValue) {
        if (hexValue == null || hexValue.isBlank()) {
            throw new IllegalArgumentException("hex value is blank");
        }
        return Numeric.toBigInt(hexValue);
    }

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }
}
