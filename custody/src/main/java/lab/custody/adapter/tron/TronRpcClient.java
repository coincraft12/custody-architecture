package lab.custody.adapter.tron;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 20-3: Thin HTTP REST client for the TRON Full Node API.
 *
 * <p>Uses Spring {@link RestClient} (Spring Boot 3.2+).
 * Adds {@code TRON-PRO-API-KEY} header when an API key is configured.
 *
 * <p>All methods return {@code Map<String, Object>} to accommodate the
 * varied JSON structures returned by different TRON API endpoints.
 */
@Slf4j
public class TronRpcClient {

    private final RestClient restClient;

    public TronRpcClient(TronRpcProperties properties) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.rpcUrl());

        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            builder.defaultHeader("TRON-PRO-API-KEY", properties.apiKey());
        }

        this.restClient = builder.build();
    }

    /**
     * Create an unsigned TRX transfer transaction.
     * POST /wallet/createtransaction
     *
     * @param ownerAddress sender TRON base58 address
     * @param toAddress    recipient TRON base58 address
     * @param amountSun    amount in SUN (1 TRX = 1,000,000 SUN)
     * @return raw transaction map as returned by TRON API
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createTransaction(String ownerAddress, String toAddress, long amountSun) {
        log.debug("event=tron_rpc.create_transaction owner={} to={} amountSun={}",
                ownerAddress, toAddress, amountSun);
        Map<String, Object> body = Map.of(
                "owner_address", ownerAddress,
                "to_address", toAddress,
                "amount", amountSun,
                "visible", true
        );
        return restClient.post()
                .uri("/wallet/createtransaction")
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Trigger a TRC-20 smart contract (token transfer).
     * POST /wallet/triggersmartcontract
     *
     * @param ownerAddress    sender TRON base58 address
     * @param contractAddress TRC-20 contract TRON base58 address
     * @param data            ABI-encoded function calldata (hex, no 0x prefix)
     * @param feeLimit        energy fee limit in SUN (e.g. 100_000_000 = 100 TRX)
     * @return trigger result map (contains "transaction" sub-object)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> triggerSmartContract(
            String ownerAddress, String contractAddress, String data, long feeLimit) {
        log.debug("event=tron_rpc.trigger_smart_contract owner={} contract={} feeLimit={}",
                ownerAddress, contractAddress, feeLimit);
        Map<String, Object> body = Map.of(
                "owner_address", ownerAddress,
                "contract_address", contractAddress,
                "function_selector", "transfer(address,uint256)",
                "parameter", data,
                "fee_limit", feeLimit,
                "call_value", 0,
                "visible", true
        );
        return restClient.post()
                .uri("/wallet/triggersmartcontract")
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Broadcast a signed transaction to the TRON network.
     * POST /wallet/broadcasttransaction
     *
     * @param signedTx map with "raw_data" and "signature" fields
     * @return broadcast result (contains "result": true on success, "txid" on success)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> broadcastTransaction(Map<String, Object> signedTx) {
        log.debug("event=tron_rpc.broadcast_transaction");
        return restClient.post()
                .uri("/wallet/broadcasttransaction")
                .body(signedTx)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Fetch a transaction by its ID.
     * POST /wallet/gettransactionbyid
     *
     * @param txId transaction hash (hex)
     * @return transaction map, or empty map if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTransactionById(String txId) {
        log.debug("event=tron_rpc.get_transaction_by_id txId={}", txId);
        Map<String, Object> body = Map.of("value", txId, "visible", true);
        return restClient.post()
                .uri("/wallet/gettransactionbyid")
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Fetch the latest block.
     * POST /wallet/getnowblock
     *
     * @return latest block map (contains block_header.raw_data.number)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getNowBlock() {
        log.debug("event=tron_rpc.get_now_block");
        return restClient.post()
                .uri("/wallet/getnowblock")
                .retrieve()
                .body(Map.class);
    }
}
