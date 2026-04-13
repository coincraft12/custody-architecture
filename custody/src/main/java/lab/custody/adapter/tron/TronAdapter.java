package lab.custody.adapter.tron;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lab.custody.adapter.BroadcastRejectedException;
import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterCapability;
import lab.custody.adapter.Erc20TransferEncoder;
import lab.custody.adapter.HeadsSnapshot;
import lab.custody.adapter.SendRequest;
import lab.custody.adapter.TxStatusSnapshot;
import lab.custody.adapter.prepared.PreparedTx;
import lab.custody.adapter.prepared.TronPreparedTx;
import lab.custody.domain.asset.AssetRegistryService;
import lab.custody.domain.asset.SupportedAsset;
import lab.custody.domain.withdrawal.ChainType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 20-6: ChainAdapter implementation for the TRON network.
 *
 * <p>Supports:
 * <ul>
 *   <li>Native TRX transfers (via {@code /wallet/createtransaction})</li>
 *   <li>TRC-20 token transfers (via {@code /wallet/triggersmartcontract})</li>
 * </ul>
 *
 * <p>Transaction signing uses SHA-256 + secp256k1, identical curve to EVM but
 * with a different hash function.  See {@link TronSigner} for details.
 *
 * <p>Activated only when {@code custody.tron.enabled=true}.
 */
@Slf4j
public class TronAdapter implements ChainAdapter {

    private final TronRpcClient rpcClient;
    private final TronSigner signer;
    private final ObjectMapper objectMapper;

    // AssetRegistryService is optional — TRX-only mode works without it.
    @Autowired(required = false)
    private AssetRegistryService assetRegistryService;

    public TronAdapter(TronRpcClient rpcClient, TronSigner signer, ObjectMapper objectMapper) {
        this.rpcClient = rpcClient;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChainType getChainType() {
        return ChainType.TRON;
    }

    @Override
    public Set<ChainAdapterCapability> capabilities() {
        // TRON does not expose an account nonce in the EVM sense (uses block-reference expiry instead),
        // but we advertise ACCOUNT_NONCE as a stub to satisfy ChainAdapter contracts.
        return Set.of(ChainAdapterCapability.ACCOUNT_NONCE);
    }

    /**
     * Build and sign a TRON transaction without broadcasting it.
     *
     * <p>For native TRX: calls {@code /wallet/createtransaction}.
     * For TRC-20: calls {@code /wallet/triggersmartcontract} with ABI-encoded calldata.
     *
     * <p>The {@code raw_data} JSON object is serialised to UTF-8 bytes and used as the
     * pre-image for SHA-256 hashing + secp256k1 signing.
     */
    @Override
    public PreparedTx prepareSend(SendRequest request) {
        if (!TronAddressUtils.isValid(request.toAddress())) {
            throw new IllegalArgumentException("Invalid TRON to-address: " + request.toAddress());
        }

        boolean isNative = resolveIsNative(request.asset());

        Map<String, Object> transaction;
        if (isNative) {
            // TRX transfer: amount in SUN (1 TRX = 1,000,000 SUN)
            Map<String, Object> rawTx = rpcClient.createTransaction(
                    signer.getAddress(),
                    request.toAddress(),
                    request.amountRaw().longValue()
            );
            transaction = rawTx;
            log.debug("event=tron.prepare_native to={} amountSun={}",
                    request.toAddress(), request.amountRaw());
        } else {
            // TRC-20 transfer: ABI-encode transfer(address,uint256)
            SupportedAsset asset = assetRegistryService.getAsset(
                    ChainType.TRON.name(), request.asset());

            // ABI encoding expects an EVM-compatible 20-byte hex address (no 0x prefix)
            String evmHex = TronAddressUtils.toEvmHex(request.toAddress());
            // Erc20TransferEncoder.encode() expects 0x-prefixed address
            String encodedData = Erc20TransferEncoder.encode("0x" + evmHex, request.amountRaw());
            // Strip the 0x prefix — TRON API expects raw hex parameter
            String parameterHex = encodedData.startsWith("0x") ? encodedData.substring(2) : encodedData;
            // Strip the 4-byte selector from the parameter (triggersmartcontract uses function_selector separately)
            // The full calldata is: selector(4 bytes) + params; TRON wants only params in 'parameter' field
            String parameter = parameterHex.length() > 8 ? parameterHex.substring(8) : parameterHex;

            Map<String, Object> triggerResult = rpcClient.triggerSmartContract(
                    signer.getAddress(),
                    asset.getContractAddress(),
                    parameter,
                    100_000_000L // fee limit: 100 TRX
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> innerTx = (Map<String, Object>) triggerResult.get("transaction");
            if (innerTx == null) {
                throw new IllegalStateException(
                        "TRON triggersmartcontract did not return a transaction object. Response: " + triggerResult);
            }
            transaction = innerTx;
            log.debug("event=tron.prepare_trc20 asset={} contract={} to={} amount={}",
                    request.asset(), asset.getContractAddress(), request.toAddress(), request.amountRaw());
        }

        // Serialize raw_data to UTF-8 JSON bytes — this is the pre-image that gets signed
        @SuppressWarnings("unchecked")
        Map<String, Object> rawData = (Map<String, Object>) transaction.get("raw_data");
        if (rawData == null) {
            throw new IllegalStateException("TRON transaction missing raw_data field: " + transaction);
        }

        String rawDataJson;
        try {
            rawDataJson = objectMapper.writeValueAsString(rawData);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise TRON raw_data to JSON", e);
        }
        byte[] rawDataBytes = rawDataJson.getBytes(StandardCharsets.UTF_8);

        // Sign: SHA-256(rawDataBytes) → secp256k1 → 65-byte sig
        byte[] signature = signer.signRaw(rawDataBytes);
        String sigHex = Numeric.toHexStringNoPrefix(signature);

        // Extract expiration from raw_data
        long expiration = extractExpiration(rawData);

        String rawDataHex = Numeric.toHexStringNoPrefix(rawDataBytes);

        return new TronPreparedTx(rawDataHex, sigHex, expiration);
    }

    /**
     * Broadcast a previously-signed TRON transaction.
     *
     * <p>Checks expiration (with a 5-second buffer) before broadcasting.
     * Throws {@link TransactionExpiredException} if expired.
     * Throws {@link BroadcastRejectedException} on network/resource errors.
     */
    @Override
    public ChainAdapter.BroadcastResult broadcast(PreparedTx prepared) {
        if (!(prepared instanceof TronPreparedTx tron)) {
            throw new IllegalArgumentException("TronAdapter requires TronPreparedTx, got: "
                    + prepared.getClass().getSimpleName());
        }

        // Check expiration with 5-second buffer
        if (System.currentTimeMillis() > tron.expirationMs() - 5_000L) {
            throw new TransactionExpiredException(
                    "TRON transaction expired (expirationMs=" + tron.expirationMs()
                    + ") — call prepareSend again");
        }

        // Reconstruct signed tx payload for broadcasttransaction
        byte[] rawDataBytes = Numeric.hexStringToByteArray(tron.rawDataHex());
        Map<String, Object> rawData;
        try {
            rawData = objectMapper.readValue(
                    new String(rawDataBytes, StandardCharsets.UTF_8), mapTypeRef());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialise TRON raw_data for broadcast", e);
        }

        Map<String, Object> signedTx = new HashMap<>();
        signedTx.put("raw_data", rawData);
        signedTx.put("signature", List.of(tron.signature()));

        Map<String, Object> result = rpcClient.broadcastTransaction(signedTx);
        boolean success = Boolean.TRUE.equals(result.get("result"));

        if (!success) {
            String message = (String) result.getOrDefault("message", "broadcast failed");
            if (message != null && (message.contains("BANDWIDTH") || message.contains("ENERGY"))) {
                throw new BroadcastRejectedException("Resource insufficient: " + message);
            }
            throw new BroadcastRejectedException("Broadcast failed: " + message);
        }

        String txid = (String) result.get("txid");
        log.info("event=tron.broadcast.success txid={}", txid);
        return new ChainAdapter.BroadcastResult(txid, true);
    }

    /**
     * Query the on-chain status of a TRON transaction.
     *
     * <p>TRON does not have a separate mempool visibility endpoint in the same way as EVM.
     * A transaction not found is UNKNOWN; presence of a blockNumber indicates INCLUDED.
     * The {@code ret[0].contractRet} field signals execution success or failure.
     */
    @Override
    public TxStatusSnapshot getTxStatus(String txHash) {
        Map<String, Object> tx = rpcClient.getTransactionById(txHash);
        if (tx == null || tx.isEmpty()) {
            return new TxStatusSnapshot(TxStatusSnapshot.TxStatus.UNKNOWN, null, null, null);
        }

        // blockNumber is present once the tx is included
        Object blockNumberObj = tx.get("blockNumber");
        if (blockNumberObj == null) {
            return new TxStatusSnapshot(TxStatusSnapshot.TxStatus.PENDING, null, null, null);
        }

        long blockNumber = ((Number) blockNumberObj).longValue();

        // Check execution result from ret[0].contractRet
        String contractRet = extractContractRet(tx);
        if ("REVERT".equals(contractRet) || "OUT_OF_ENERGY".equals(contractRet)
                || "FAILED".equals(contractRet)) {
            return new TxStatusSnapshot(TxStatusSnapshot.TxStatus.FAILED, blockNumber, null, contractRet);
        }

        return new TxStatusSnapshot(TxStatusSnapshot.TxStatus.INCLUDED, blockNumber, null, null);
    }

    /**
     * Return the current TRON block height.
     *
     * <p>TRON does not expose "safe" or "finalized" checkpoints in the EVM sense
     * (DPoS consensus has immediate soft finality); both are returned as null.
     */
    @Override
    public HeadsSnapshot getHeads() {
        Map<String, Object> block = rpcClient.getNowBlock();
        @SuppressWarnings("unchecked")
        Map<String, Object> blockHeader = (Map<String, Object>) block.get("block_header");
        if (blockHeader == null) {
            throw new IllegalStateException("TRON getNowBlock response missing block_header: " + block);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rawData = (Map<String, Object>) blockHeader.get("raw_data");
        if (rawData == null) {
            throw new IllegalStateException("TRON block_header missing raw_data: " + blockHeader);
        }
        long number = ((Number) rawData.get("number")).longValue();
        return new HeadsSnapshot(number, null, null, System.currentTimeMillis());
    }

    // ─── private helpers ───

    private boolean resolveIsNative(String asset) {
        if (asset == null || "TRX".equalsIgnoreCase(asset)) return true;
        if (assetRegistryService == null) return true; // fallback
        try {
            return assetRegistryService.getAsset(ChainType.TRON.name(), asset).isNative();
        } catch (Exception e) {
            log.warn("event=tron.asset_lookup_failed asset={} error={}", asset, e.getMessage());
            return false;
        }
    }

    private long extractExpiration(Map<String, Object> rawData) {
        Object expObj = rawData.get("expiration");
        if (expObj instanceof Number n) {
            return n.longValue();
        }
        // Default: now + 60 seconds if not present
        return System.currentTimeMillis() + 60_000L;
    }

    @SuppressWarnings("unchecked")
    private String extractContractRet(Map<String, Object> tx) {
        Object retObj = tx.get("ret");
        if (retObj instanceof List<?> retList && !retList.isEmpty()) {
            Object firstRet = retList.get(0);
            if (firstRet instanceof Map<?, ?> retMap) {
                Object cr = retMap.get("contractRet");
                return cr != null ? cr.toString() : null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Class<Map<String, Object>> mapTypeRef() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }
}
