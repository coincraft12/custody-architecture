package lab.custody.adapter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lab.custody.adapter.prepared.EvmPreparedTx;
import lab.custody.adapter.prepared.PreparedTx;
import lab.custody.domain.asset.AssetRegistryService;
import lab.custody.domain.asset.SupportedAsset;
import lab.custody.domain.withdrawal.ChainType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 18-4: EvmRpcAdapter ERC-20 지원 단위 테스트.
 *
 * 검증 항목:
 *  1. ETH 전송 시 ERC-20 calldata 없음 (value = amountRaw)
 *  2. USDC 전송 시 to = 컨트랙트, value = 0, gasLimit = SupportedAsset.defaultGasLimit
 *  3. USDC 전송 시 Erc20TransferEncoder.encode() 결과가 calldata에 반영됨
 *  4. AssetRegistryService가 null(미주입)이면 ETH native fallback 동작
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvmRpcAdapterErc20Test {

    private static final long CHAIN_ID = 1L;
    private static final String USDC_CONTRACT = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
    private static final String FROM_ADDR = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    private static final String TO_ADDR   = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    // USDC defaultGasLimit from SupportedAsset
    private static final long USDC_GAS_LIMIT = 65_000L;

    @Mock
    private AssetRegistryService assetRegistryService;

    @Mock
    private Signer signer;

    @Mock
    private EvmRpcProviderPool providerPool;

    @Mock
    private Web3j web3j;

    // Captures the RawTransaction passed to signer.sign()
    private final ArgumentCaptor<RawTransaction> rawTxCaptor = ArgumentCaptor.forClass(RawTransaction.class);

    private EvmRpcAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        adapter = new EvmRpcAdapter(
                providerPool, CHAIN_ID, signer, new SimpleMeterRegistry(), 110);
        // Inject optional AssetRegistryService via reflection (field injection)
        ReflectionTestUtils.setField(adapter, "assetRegistryService", assetRegistryService);

        // Stub providerPool to return mock web3j
        when(providerPool.primary()).thenReturn(web3j);
        when(providerPool.size()).thenReturn(1);
        when(providerPool.get(0)).thenReturn(web3j);

        // Stub chain-id check
        EthChainId chainIdResponse = mock(EthChainId.class);
        when(chainIdResponse.hasError()).thenReturn(false);
        when(chainIdResponse.getChainId()).thenReturn(BigInteger.valueOf(CHAIN_ID));
        @SuppressWarnings("unchecked")
        Request<Object, EthChainId> chainIdRequest = mock(Request.class);
        when(chainIdRequest.send()).thenReturn(chainIdResponse);
        doReturn(chainIdRequest).when(web3j).ethChainId();

        // Stub nonce fetch
        EthGetTransactionCount txCountResponse = mock(EthGetTransactionCount.class);
        when(txCountResponse.hasError()).thenReturn(false);
        when(txCountResponse.getTransactionCount()).thenReturn(BigInteger.valueOf(42L));
        @SuppressWarnings("unchecked")
        Request<Object, EthGetTransactionCount> nonceRequest = mock(Request.class);
        when(nonceRequest.send()).thenReturn(txCountResponse);
        doReturn(nonceRequest).when(web3j).ethGetTransactionCount(anyString(), any());

        // Signer returns a dummy hex
        when(signer.getAddress()).thenReturn(FROM_ADDR);
        when(signer.sign(any(RawTransaction.class), eq(CHAIN_ID))).thenReturn("0xsigned");

        // Gas price: inject cache directly to skip RPC calls
        injectGasPriceCache();
    }

    /** Injects a pre-built GasPriceCache so fetchGasPrices() returns without RPC calls. */
    private void injectGasPriceCache() {
        // EvmRpcAdapter.GasPriceCache is a private record — use reflection via AtomicReference
        BigInteger priorityFee = BigInteger.valueOf(2_000_000_000L);
        BigInteger maxFee      = BigInteger.valueOf(20_000_000_000L);
        // Build a GasPriceCache with future timestamp so it is always valid
        Object cache = buildGasPriceCache(priorityFee, maxFee, Long.MAX_VALUE / 2);
        java.util.concurrent.atomic.AtomicReference<?> ref =
                (java.util.concurrent.atomic.AtomicReference<?>) ReflectionTestUtils.getField(adapter, "gasPriceCache");
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<Object> typed =
                (java.util.concurrent.atomic.AtomicReference<Object>) ref;
        typed.set(cache);
    }

    /** Reflectively constructs EvmRpcAdapter's private GasPriceCache record. */
    private Object buildGasPriceCache(BigInteger priority, BigInteger max, long ts) {
        try {
            // GasPriceCache is a private static record inside EvmRpcAdapter
            Class<?>[] inner = EvmRpcAdapter.class.getDeclaredClasses();
            Class<?> cacheClass = null;
            for (Class<?> c : inner) {
                if (c.getSimpleName().equals("GasPriceCache")) {
                    cacheClass = c;
                    break;
                }
            }
            if (cacheClass == null) throw new IllegalStateException("GasPriceCache class not found");
            java.lang.reflect.Constructor<?> ctor = cacheClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(priority, max, ts);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build GasPriceCache via reflection", e);
        }
    }

    // ─── ETH (native) transfer ───────────────────────────────────────────────

    @Test
    void prepareSend_ethTransfer_rawTxIsEtherTransaction() {
        // Arrange: ETH is native
        stubEth();

        SendRequest req = new SendRequest(ChainType.EVM, "ETH", TO_ADDR,
                BigInteger.valueOf(1_000_000_000_000_000_000L), FROM_ADDR, "idm-1");

        // Act
        PreparedTx prepared = adapter.prepareSend(req);

        // Assert: signer was called with the raw tx
        verify(signer).sign(rawTxCaptor.capture(), eq(CHAIN_ID));
        RawTransaction rawTx = rawTxCaptor.getValue();

        assertThat(rawTx).isNotNull();
        // Native ETH tx: to = recipient
        assertThat(rawTx.getTo()).isEqualTo(TO_ADDR);
        // value = amountRaw
        assertThat(rawTx.getValue()).isEqualTo(BigInteger.valueOf(1_000_000_000_000_000_000L));
        // data = empty (no calldata for ETH transfer)
        assertThat(rawTx.getData()).isIn(null, "", "0x");
        // gasLimit = 21_000 (ETH default)
        assertThat(rawTx.getGasLimit()).isEqualTo(BigInteger.valueOf(21_000L));

        assertThat(prepared).isInstanceOf(EvmPreparedTx.class);
        assertThat(((EvmPreparedTx) prepared).signedHexTx()).isEqualTo("0xsigned");
    }

    // ─── USDC (ERC-20) transfer ──────────────────────────────────────────────

    @Test
    void prepareSend_usdcTransfer_rawTxToIsContractAddress() {
        // Arrange: USDC is ERC-20
        stubUsdc();

        BigInteger usdcAmount = BigInteger.valueOf(100_000_000L); // 100 USDC
        SendRequest req = new SendRequest(ChainType.EVM, "USDC", TO_ADDR, usdcAmount, FROM_ADDR, "idm-2");

        // Act
        adapter.prepareSend(req);

        // Assert: raw tx to = USDC contract (lowercase)
        verify(signer).sign(rawTxCaptor.capture(), eq(CHAIN_ID));
        RawTransaction rawTx = rawTxCaptor.getValue();

        assertThat(rawTx.getTo()).isEqualToIgnoringCase(USDC_CONTRACT);
    }

    @Test
    void prepareSend_usdcTransfer_valueIsZero() {
        // ERC-20 transfer carries no ETH value
        stubUsdc();

        BigInteger usdcAmount = BigInteger.valueOf(50_000_000L);
        SendRequest req = new SendRequest(ChainType.EVM, "USDC", TO_ADDR, usdcAmount, FROM_ADDR, "idm-3");

        adapter.prepareSend(req);

        verify(signer).sign(rawTxCaptor.capture(), eq(CHAIN_ID));
        assertThat(rawTxCaptor.getValue().getValue()).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void prepareSend_usdcTransfer_dataIsAbiEncodedTransfer() {
        stubUsdc();

        BigInteger usdcAmount = BigInteger.valueOf(100_000_000L);
        SendRequest req = new SendRequest(ChainType.EVM, "USDC", TO_ADDR, usdcAmount, FROM_ADDR, "idm-4");

        adapter.prepareSend(req);

        verify(signer).sign(rawTxCaptor.capture(), eq(CHAIN_ID));
        String data = rawTxCaptor.getValue().getData();

        // Erc20TransferEncoder.encode() returns "0x..." but RawTransaction.getData() strips the "0x" prefix
        String expectedData = Erc20TransferEncoder.encode(TO_ADDR, usdcAmount);
        String expectedDataStripped = expectedData.startsWith("0x") ? expectedData.substring(2) : expectedData;
        assertThat(data).isEqualToIgnoringCase(expectedDataStripped);
        // Selector check (without 0x prefix, as stored by web3j)
        assertThat(data).startsWith("a9059cbb");
    }

    @Test
    void prepareSend_usdcTransfer_gasLimitIsFromAssetRegistry() {
        stubUsdc();

        BigInteger usdcAmount = BigInteger.valueOf(1_000_000L);
        SendRequest req = new SendRequest(ChainType.EVM, "USDC", TO_ADDR, usdcAmount, FROM_ADDR, "idm-5");

        adapter.prepareSend(req);

        verify(signer).sign(rawTxCaptor.capture(), eq(CHAIN_ID));
        assertThat(rawTxCaptor.getValue().getGasLimit()).isEqualTo(BigInteger.valueOf(USDC_GAS_LIMIT));
    }

    // ─── Fallback when AssetRegistryService is absent ───────────────────────

    @Test
    void prepareSend_noAssetRegistry_fallsBackToEthNative() {
        // Remove AssetRegistryService — simulate absent bean
        ReflectionTestUtils.setField(adapter, "assetRegistryService", null);

        SendRequest req = new SendRequest(ChainType.EVM, "ETH", TO_ADDR,
                BigInteger.valueOf(1_000L), FROM_ADDR, "idm-6");

        adapter.prepareSend(req);

        verify(signer).sign(rawTxCaptor.capture(), eq(CHAIN_ID));
        RawTransaction rawTx = rawTxCaptor.getValue();

        // No AssetRegistryService → must use ETH native path
        assertThat(rawTx.getTo()).isEqualTo(TO_ADDR);
        assertThat(rawTx.getValue()).isEqualTo(BigInteger.valueOf(1_000L));
        assertThat(rawTx.getGasLimit()).isEqualTo(BigInteger.valueOf(21_000L));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private SupportedAsset ethAsset() {
        return SupportedAsset.builder()
                .id(UUID.randomUUID())
                .assetSymbol("ETH")
                .chainType("EVM")
                .contractAddress(null)
                .decimals(18)
                .defaultGasLimit(21_000L)
                .enabled(true)
                .native_(true)
                .createdAt(Instant.now())
                .build();
    }

    private SupportedAsset usdcAsset() {
        return SupportedAsset.builder()
                .id(UUID.randomUUID())
                .assetSymbol("USDC")
                .chainType("EVM")
                .contractAddress(USDC_CONTRACT)
                .decimals(6)
                .defaultGasLimit(USDC_GAS_LIMIT)
                .enabled(true)
                .native_(false)
                .createdAt(Instant.now())
                .build();
    }

    private void stubEth() {
        when(assetRegistryService.getAsset("EVM", "ETH")).thenReturn(ethAsset());
    }

    private void stubUsdc() {
        when(assetRegistryService.getAsset("EVM", "USDC")).thenReturn(usdcAsset());
    }
}
