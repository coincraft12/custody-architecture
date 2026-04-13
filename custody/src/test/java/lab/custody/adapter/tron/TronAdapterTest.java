package lab.custody.adapter.tron;

import com.fasterxml.jackson.databind.ObjectMapper;
import lab.custody.adapter.BroadcastRejectedException;
import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.HeadsSnapshot;
import lab.custody.adapter.SendRequest;
import lab.custody.adapter.TxStatusSnapshot;
import lab.custody.adapter.prepared.PreparedTx;
import lab.custody.adapter.prepared.TronPreparedTx;
import lab.custody.domain.asset.AssetRegistryService;
import lab.custody.domain.asset.SupportedAsset;
import lab.custody.domain.withdrawal.ChainType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 20-9: Unit tests for {@link TronAdapter}.
 *
 * <p>All external dependencies (RPC client, signer, asset registry) are mocked.
 */
@ExtendWith(MockitoExtension.class)
class TronAdapterTest {

    @Mock
    private TronRpcClient rpcClient;

    @Mock
    private TronSigner signer;

    @Mock
    private AssetRegistryService assetRegistryService;

    @Mock
    private SupportedAsset usdtAsset;

    private TronAdapter adapter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String FROM_ADDRESS = "TRvBupSkJ7UcPcJFP5BLnhpj3KFbxFTqhN";
    private static final String TO_ADDRESS   = "TN3W4H6rK2ce4vX9YnFQHwKENnHjoxb3m9";
    // A realistic TRON contract address (Nile testnet USDT)
    private static final String USDT_CONTRACT = "TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf";

    @BeforeEach
    void setUp() {
        adapter = new TronAdapter(rpcClient, signer, objectMapper);
        // Inject optional assetRegistryService via reflection (simulating @Autowired(required=false))
        ReflectionTestUtils.setField(adapter, "assetRegistryService", assetRegistryService);

        given(signer.getAddress()).willReturn(FROM_ADDRESS);
    }

    // ──────────────────────────────────────────────────────────────────────
    // prepareSend — native TRX
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("prepareSend native TRX → calls createTransaction, returns TronPreparedTx")
    void prepareSend_nativeTrx_callsCreateTransaction() {
        long amountSun = 1_000_000L; // 1 TRX
        long expirationMs = System.currentTimeMillis() + 60_000L;

        Map<String, Object> rawData = Map.of(
                "expiration", expirationMs,
                "ref_block_bytes", "abcd",
                "ref_block_hash", "12345678"
        );
        Map<String, Object> fakeTx = Map.of("raw_data", rawData);

        given(rpcClient.createTransaction(anyString(), anyString(), anyLong()))
                .willReturn(fakeTx);
        given(signer.signRaw(any(byte[].class)))
                .willReturn(new byte[65]);

        SendRequest request = new SendRequest(
                ChainType.TRON, "TRX", TO_ADDRESS, BigInteger.valueOf(amountSun),
                FROM_ADDRESS, "idem-1");

        PreparedTx prepared = adapter.prepareSend(request);

        assertThat(prepared).isInstanceOf(TronPreparedTx.class);
        TronPreparedTx tron = (TronPreparedTx) prepared;
        assertThat(tron.expirationMs()).isEqualTo(expirationMs);
        assertThat(tron.signature()).isNotBlank();
        assertThat(tron.rawDataHex()).isNotBlank();

        verify(rpcClient).createTransaction(FROM_ADDRESS, TO_ADDRESS, amountSun);
        verify(rpcClient, never()).triggerSmartContract(anyString(), anyString(), anyString(), anyLong());
    }

    // ──────────────────────────────────────────────────────────────────────
    // prepareSend — TRC-20 USDT
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("prepareSend TRC-20 USDT → calls triggerSmartContract with ABI-encoded data")
    void prepareSend_trc20Usdt_callsTriggerSmartContract() {
        BigInteger amount = BigInteger.valueOf(1_000_000L); // 1 USDT (6 decimals)
        long expirationMs = System.currentTimeMillis() + 60_000L;

        given(assetRegistryService.getAsset(eq("TRON"), eq("USDT"))).willReturn(usdtAsset);
        given(usdtAsset.isNative()).willReturn(false);
        given(usdtAsset.getContractAddress()).willReturn(USDT_CONTRACT);

        Map<String, Object> rawData = Map.of(
                "expiration", expirationMs,
                "ref_block_bytes", "abcd"
        );
        Map<String, Object> innerTx = Map.of("raw_data", rawData);
        Map<String, Object> triggerResult = Map.of("transaction", innerTx);

        given(rpcClient.triggerSmartContract(anyString(), anyString(), anyString(), anyLong()))
                .willReturn(triggerResult);
        given(signer.signRaw(any(byte[].class)))
                .willReturn(new byte[65]);

        SendRequest request = new SendRequest(
                ChainType.TRON, "USDT", TO_ADDRESS, amount, FROM_ADDRESS, "idem-2");

        PreparedTx prepared = adapter.prepareSend(request);

        assertThat(prepared).isInstanceOf(TronPreparedTx.class);

        verify(rpcClient).triggerSmartContract(
                eq(FROM_ADDRESS), eq(USDT_CONTRACT), anyString(), eq(100_000_000L));
        verify(rpcClient, never()).createTransaction(anyString(), anyString(), anyLong());
    }

    // ──────────────────────────────────────────────────────────────────────
    // broadcast — expired transaction
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("broadcast with expired tx → throws TransactionExpiredException")
    void broadcast_expiredTx_throwsTransactionExpiredException() {
        long pastExpiration = System.currentTimeMillis() - 1_000L; // already expired
        String rawDataHex = Numeric.toHexStringNoPrefix(
                "{\"expiration\":0}".getBytes(StandardCharsets.UTF_8));
        TronPreparedTx expired = new TronPreparedTx(rawDataHex, "deadbeef", pastExpiration);

        assertThatThrownBy(() -> adapter.broadcast(expired))
                .isInstanceOf(TransactionExpiredException.class)
                .hasMessageContaining("expired");
    }

    // ──────────────────────────────────────────────────────────────────────
    // broadcast — BANDWIDTH error
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("broadcast with BANDWIDTH_ERROR response → throws BroadcastRejectedException")
    void broadcast_bandwidthError_throwsBroadcastRejectedException() throws Exception {
        long futureExpiration = System.currentTimeMillis() + 60_000L;
        Map<String, Object> rawDataMap = Map.of("expiration", futureExpiration, "ref_block_bytes", "abcd");
        String rawDataJson = objectMapper.writeValueAsString(rawDataMap);
        String rawDataHex = Numeric.toHexStringNoPrefix(rawDataJson.getBytes(StandardCharsets.UTF_8));

        TronPreparedTx tx = new TronPreparedTx(rawDataHex, "aabbcc", futureExpiration);

        given(rpcClient.broadcastTransaction(any()))
                .willReturn(Map.of("result", false, "message", "BANDWIDTH_ERROR"));

        assertThatThrownBy(() -> adapter.broadcast(tx))
                .isInstanceOf(BroadcastRejectedException.class)
                .hasMessageContaining("BANDWIDTH");
    }

    // ──────────────────────────────────────────────────────────────────────
    // broadcast — success
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("broadcast with valid tx → returns BroadcastResult with txid")
    void broadcast_success_returnsTxid() throws Exception {
        long futureExpiration = System.currentTimeMillis() + 60_000L;
        Map<String, Object> rawDataMap = Map.of("expiration", futureExpiration);
        String rawDataJson = objectMapper.writeValueAsString(rawDataMap);
        String rawDataHex = Numeric.toHexStringNoPrefix(rawDataJson.getBytes(StandardCharsets.UTF_8));

        TronPreparedTx tx = new TronPreparedTx(rawDataHex, "aabbcc", futureExpiration);

        given(rpcClient.broadcastTransaction(any()))
                .willReturn(Map.of("result", true, "txid", "abc123def456"));

        ChainAdapter.BroadcastResult result = adapter.broadcast(tx);

        assertThat(result.accepted()).isTrue();
        assertThat(result.txHash()).isEqualTo("abc123def456");
    }

    // ──────────────────────────────────────────────────────────────────────
    // getTxStatus — no blockNumber → PENDING
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getTxStatus with no blockNumber → PENDING")
    void getTxStatus_noBlockNumber_returnsPending() {
        given(rpcClient.getTransactionById("hash1"))
                .willReturn(Map.of("txID", "hash1", "raw_data", Map.of()));

        TxStatusSnapshot snapshot = adapter.getTxStatus("hash1");

        assertThat(snapshot.status()).isEqualTo(TxStatusSnapshot.TxStatus.PENDING);
        assertThat(snapshot.blockNumber()).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────
    // getTxStatus — not found → UNKNOWN
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getTxStatus with empty response → UNKNOWN")
    void getTxStatus_emptyResponse_returnsUnknown() {
        given(rpcClient.getTransactionById("notfound"))
                .willReturn(Map.of());

        TxStatusSnapshot snapshot = adapter.getTxStatus("notfound");

        assertThat(snapshot.status()).isEqualTo(TxStatusSnapshot.TxStatus.UNKNOWN);
    }

    // ──────────────────────────────────────────────────────────────────────
    // getTxStatus — blockNumber present → INCLUDED
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getTxStatus with blockNumber → INCLUDED")
    void getTxStatus_withBlockNumber_returnsIncluded() {
        Map<String, Object> ret0 = Map.of("contractRet", "SUCCESS");
        given(rpcClient.getTransactionById("hash2"))
                .willReturn(Map.of(
                        "txID", "hash2",
                        "blockNumber", 12345678L,
                        "ret", List.of(ret0)
                ));

        TxStatusSnapshot snapshot = adapter.getTxStatus("hash2");

        assertThat(snapshot.status()).isEqualTo(TxStatusSnapshot.TxStatus.INCLUDED);
        assertThat(snapshot.blockNumber()).isEqualTo(12345678L);
    }

    // ──────────────────────────────────────────────────────────────────────
    // getTxStatus — OUT_OF_ENERGY → FAILED
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getTxStatus with OUT_OF_ENERGY contractRet → FAILED")
    void getTxStatus_outOfEnergy_returnsFailed() {
        Map<String, Object> ret0 = Map.of("contractRet", "OUT_OF_ENERGY");
        given(rpcClient.getTransactionById("hash3"))
                .willReturn(Map.of(
                        "txID", "hash3",
                        "blockNumber", 9999L,
                        "ret", List.of(ret0)
                ));

        TxStatusSnapshot snapshot = adapter.getTxStatus("hash3");

        assertThat(snapshot.status()).isEqualTo(TxStatusSnapshot.TxStatus.FAILED);
        assertThat(snapshot.revertReason()).isEqualTo("OUT_OF_ENERGY");
    }

    // ──────────────────────────────────────────────────────────────────────
    // getHeads — safeBlock and finalizedBlock must be null
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHeads → latestBlock from getNowBlock, safeBlock=null, finalizedBlock=null")
    void getHeads_returnsSafeAndFinalizedAsNull() {
        Map<String, Object> rawData = Map.of("number", 55_000_000L, "timestamp", 1700000000000L);
        Map<String, Object> blockHeader = Map.of("raw_data", rawData);
        given(rpcClient.getNowBlock()).willReturn(Map.of("block_header", blockHeader));

        HeadsSnapshot heads = adapter.getHeads();

        assertThat(heads.latestBlock()).isEqualTo(55_000_000L);
        assertThat(heads.safeBlock()).isNull();
        assertThat(heads.finalizedBlock()).isNull();
    }
}
