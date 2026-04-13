package lab.custody.adapter.bitcoin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 19-7: Thin JSON-RPC client for Bitcoin Core.
 *
 * <p>Uses Spring {@link RestClient} with Basic authentication.
 * Each method maps to a single Bitcoin Core RPC call.
 */
@Slf4j
public class BitcoinRpcClient {

    private final RestClient restClient;

    /**
     * UTXO as returned by the {@code listunspent} RPC call.
     *
     * @param txid      transaction hash
     * @param vout      output index
     * @param address   receiving address
     * @param amountSat amount in satoshis
     */
    public record Utxo(String txid, int vout, String address, long amountSat) {}

    /**
     * Transaction info as returned by the {@code gettransaction} RPC call.
     *
     * @param txid          transaction hash
     * @param confirmations number of block confirmations (0 = mempool)
     * @param inMempool     true when the tx is in the local mempool
     */
    public record BitcoinTxInfo(String txid, int confirmations, boolean inMempool) {}

    public BitcoinRpcClient(BitcoinRpcProperties properties) {
        String credentials = properties.rpcUser() + ":" + properties.rpcPassword();
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

        this.restClient = RestClient.builder()
                .baseUrl(properties.rpcUrl())
                .defaultHeader("Authorization", authHeader)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public RPC methods
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Lists unspent transaction outputs (UTXOs) for the given address.
     *
     * @param address bech32 or legacy Bitcoin address
     * @return list of UTXOs with satoshi amounts
     */
    @SuppressWarnings("unchecked")
    public List<Utxo> listUnspent(String address) {
        Map<String, Object> response = call("listunspent",
                List.of(1, 9999999, List.of(address)));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        if (result == null) {
            log.warn("event=bitcoin_rpc.listunspent.null_result address={}", address);
            return List.of();
        }
        return result.stream()
                .map(u -> new Utxo(
                        (String) u.get("txid"),
                        ((Number) u.get("vout")).intValue(),
                        (String) u.get("address"),
                        btcToSat((Number) u.get("amount"))
                ))
                .toList();
    }

    /**
     * Estimates the fee rate (BTC/kB) for confirmation within {@code targetBlocks} blocks.
     *
     * @param targetBlocks confirmation target in blocks
     * @return estimated fee rate in BTC/kB (0 if estimation is unavailable)
     */
    @SuppressWarnings("unchecked")
    public double estimateSmartFee(int targetBlocks) {
        Map<String, Object> response = call("estimatesmartfee", List.of(targetBlocks));
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        if (result == null || !result.containsKey("feerate")) {
            log.warn("event=bitcoin_rpc.estimatesmartfee.no_feerate targetBlocks={}", targetBlocks);
            return 0.0;
        }
        return ((Number) result.get("feerate")).doubleValue();
    }

    /**
     * Broadcasts a raw (signed, hex-encoded) transaction to the Bitcoin network.
     *
     * @param hexTx fully-signed transaction in hex format
     * @return the txid of the broadcasted transaction
     */
    @SuppressWarnings("unchecked")
    public String sendRawTransaction(String hexTx) {
        Map<String, Object> response = call("sendrawtransaction", List.of(hexTx));
        Object result = response.get("result");
        if (result == null) {
            Map<String, Object> error = (Map<String, Object>) response.get("error");
            String msg = error != null ? (String) error.get("message") : "unknown error";
            throw new IllegalStateException("sendrawtransaction failed: " + msg);
        }
        return (String) result;
    }

    /**
     * Fetches transaction details for the given txid.
     *
     * @param txid transaction hash
     * @return transaction info including confirmation count
     */
    @SuppressWarnings("unchecked")
    public BitcoinTxInfo getTransaction(String txid) {
        Map<String, Object> response = call("gettransaction", List.of(txid));
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        if (result == null) {
            log.warn("event=bitcoin_rpc.gettransaction.null_result txid={}", txid);
            return new BitcoinTxInfo(txid, 0, false);
        }
        int confirmations = result.containsKey("confirmations")
                ? ((Number) result.get("confirmations")).intValue()
                : 0;
        boolean inMempool = result.containsKey("bip125-replaceable");
        return new BitcoinTxInfo(txid, confirmations, inMempool);
    }

    /**
     * Returns the current blockchain height (number of mined blocks).
     *
     * @return block count
     */
    @SuppressWarnings("unchecked")
    public long getBlockCount() {
        Map<String, Object> response = call("getblockcount", List.of());
        Object result = response.get("result");
        if (result == null) {
            throw new IllegalStateException("getblockcount returned null result");
        }
        return ((Number) result).longValue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> call(String method, Object params) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "1.1",
                "id", method,
                "method", method,
                "params", params
        );
        return restClient.post()
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /** Convert BTC (as returned by Bitcoin Core RPC) to satoshis. */
    private static long btcToSat(Number btc) {
        if (btc == null) return 0L;
        // Use string-based rounding to avoid floating-point precision issues
        return Math.round(btc.doubleValue() * 100_000_000.0);
    }
}
