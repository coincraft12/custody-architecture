package lab.custody.adapter.bitcoin;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.HeadsSnapshot;
import lab.custody.adapter.SendRequest;
import lab.custody.adapter.TxStatusSnapshot;
import lab.custody.adapter.prepared.BitcoinPreparedTx;
import lab.custody.adapter.prepared.PreparedTx;
import lab.custody.domain.bitcoin.UtxoLock;
import lab.custody.domain.bitcoin.UtxoLockRepository;
import lab.custody.domain.withdrawal.ChainType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 19-12: Unit tests for {@link BitcoinAdapter}.
 *
 * <p>All external dependencies (RPC client, signer, repository) are mocked.
 */
@ExtendWith(MockitoExtension.class)
class BitcoinAdapterTest {

    @Mock
    private BitcoinRpcClient rpcClient;

    @Mock
    private BitcoinSigner signer;

    @Mock
    private UtxoLockRepository utxoLockRepository;

    private BitcoinRpcProperties properties;
    private BitcoinAdapter adapter;

    private static final String FROM_ADDRESS = "bcrt1qtest000from";
    private static final String TO_ADDRESS   = "bcrt1qtest000to";
    private static final String SIGNED_HEX   = "deadbeef01020304";

    @BeforeEach
    void setUp() {
        properties = new BitcoinRpcProperties(
                "http://localhost:18443",
                "bitcoin",
                "bitcoin",
                "regtest",
                10
        );
        adapter = new BitcoinAdapter(rpcClient, signer, utxoLockRepository, properties);
    }

    // ──────────────────────────────────────────────────────────────────────
    // prepareSend — UTXO selection
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UTXO selection: 2 unlocked UTXOs available → selects minimum needed (largest first)")
    void prepareSend_selectsMinimumNeededUtxos() {
        // 10_000 sat + 2_000 sat UTXOs; send 8_000 sat
        // Expected: only the 10_000 sat UTXO is needed
        List<BitcoinRpcClient.Utxo> utxos = List.of(
                new BitcoinRpcClient.Utxo("aaa", 0, FROM_ADDRESS, 10_000L),
                new BitcoinRpcClient.Utxo("bbb", 0, FROM_ADDRESS, 2_000L)
        );

        given(rpcClient.listUnspent(FROM_ADDRESS)).willReturn(utxos);
        given(utxoLockRepository.findAllLockedUtxoKeys()).willReturn(List.of());
        given(rpcClient.estimateSmartFee(anyInt())).willReturn(0.00001); // ~1000 sat fee
        given(utxoLockRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(signer.signTransaction(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyList()))
                .willReturn(SIGNED_HEX);

        SendRequest request = new SendRequest(
                ChainType.BITCOIN, "BTC", TO_ADDRESS, BigInteger.valueOf(8_000L), FROM_ADDRESS, "key-1");

        PreparedTx result = adapter.prepareSend(request);

        assertThat(result).isInstanceOf(BitcoinPreparedTx.class);
        BitcoinPreparedTx btcTx = (BitcoinPreparedTx) result;
        assertThat(btcTx.rawHex()).isEqualTo(SIGNED_HEX);
        // Only "aaa:0" should be locked (10k sat covers 8k + fee; 2k UTXO not needed)
        assertThat(btcTx.lockedUtxoKeys()).containsExactly("aaa:0");
    }

    @Test
    @DisplayName("UTXO selection: locked UTXO is excluded from selection")
    void prepareSend_excludesLockedUtxo() {
        List<BitcoinRpcClient.Utxo> utxos = List.of(
                new BitcoinRpcClient.Utxo("locked-tx", 0, FROM_ADDRESS, 50_000L),
                new BitcoinRpcClient.Utxo("free-tx",   0, FROM_ADDRESS, 50_000L)
        );

        // "locked-tx:0" is already locked
        given(rpcClient.listUnspent(FROM_ADDRESS)).willReturn(utxos);
        given(utxoLockRepository.findAllLockedUtxoKeys()).willReturn(List.of("locked-tx:0"));
        given(rpcClient.estimateSmartFee(anyInt())).willReturn(0.00001);
        given(utxoLockRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(signer.signTransaction(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyList()))
                .willReturn(SIGNED_HEX);

        SendRequest request = new SendRequest(
                ChainType.BITCOIN, "BTC", TO_ADDRESS, BigInteger.valueOf(20_000L), FROM_ADDRESS, "key-2");

        PreparedTx result = adapter.prepareSend(request);
        BitcoinPreparedTx btcTx = (BitcoinPreparedTx) result;

        // Only the free UTXO should appear in the locked keys list
        assertThat(btcTx.lockedUtxoKeys()).containsExactly("free-tx:0");
        assertThat(btcTx.lockedUtxoKeys()).doesNotContain("locked-tx:0");
    }

    @Test
    @DisplayName("Dust change absorbed into fee when change < 546 sat")
    void prepareSend_absorbsDustChange() {
        // UTXO: 10_000 sat. Send: 9_800 sat. Fee estimate: ~100 sat.
        // Expected change = 10000 - 9800 - fee. If change < 546 → absorbed.
        List<BitcoinRpcClient.Utxo> utxos = List.of(
                new BitcoinRpcClient.Utxo("dust-tx", 0, FROM_ADDRESS, 10_000L)
        );

        given(rpcClient.listUnspent(FROM_ADDRESS)).willReturn(utxos);
        given(utxoLockRepository.findAllLockedUtxoKeys()).willReturn(List.of());
        // Very low fee rate so fee is small enough that change will be < 546 sat
        given(rpcClient.estimateSmartFee(anyInt())).willReturn(0.000001); // ~100 sat
        given(utxoLockRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // Capture the changeSat argument to verify dust absorption
        ArgumentCaptor<Long> changeSatCaptor = ArgumentCaptor.forClass(Long.class);
        given(signer.signTransaction(anyString(), anyLong(), anyLong(), anyString(),
                changeSatCaptor.capture(), anyList())).willReturn(SIGNED_HEX);

        SendRequest request = new SendRequest(
                ChainType.BITCOIN, "BTC", TO_ADDRESS, BigInteger.valueOf(9_800L), FROM_ADDRESS, "key-3");

        PreparedTx result = adapter.prepareSend(request);

        // Change passed to signer must be 0 (dust absorbed)
        assertThat(changeSatCaptor.getValue()).isZero();
    }

    @Test
    @DisplayName("prepareSend throws InsufficientFundsException when UTXOs cannot cover amount + fee")
    void prepareSend_throwsWhenInsufficientFunds() {
        List<BitcoinRpcClient.Utxo> utxos = List.of(
                new BitcoinRpcClient.Utxo("small-tx", 0, FROM_ADDRESS, 500L)
        );

        given(rpcClient.listUnspent(FROM_ADDRESS)).willReturn(utxos);
        given(utxoLockRepository.findAllLockedUtxoKeys()).willReturn(List.of());
        given(rpcClient.estimateSmartFee(anyInt())).willReturn(0.00001);

        SendRequest request = new SendRequest(
                ChainType.BITCOIN, "BTC", TO_ADDRESS, BigInteger.valueOf(1_000_000L), FROM_ADDRESS, "key-4");

        assertThatThrownBy(() -> adapter.prepareSend(request))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient unlocked UTXOs");
    }

    // ──────────────────────────────────────────────────────────────────────
    // getTxStatus
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getTxStatus: 0 confirmations → PENDING")
    void getTxStatus_zeroConfirmations_returnsPending() {
        given(rpcClient.getTransaction("txhash"))
                .willReturn(new BitcoinRpcClient.BitcoinTxInfo("txhash", 0, true));

        TxStatusSnapshot snapshot = adapter.getTxStatus("txhash");

        assertThat(snapshot.status()).isEqualTo(TxStatusSnapshot.TxStatus.PENDING);
        assertThat(snapshot.confirmations()).isZero();
    }

    @Test
    @DisplayName("getTxStatus: 1 confirmation → INCLUDED")
    void getTxStatus_oneConfirmation_returnsIncluded() {
        given(rpcClient.getTransaction("txhash"))
                .willReturn(new BitcoinRpcClient.BitcoinTxInfo("txhash", 1, false));

        TxStatusSnapshot snapshot = adapter.getTxStatus("txhash");

        assertThat(snapshot.status()).isEqualTo(TxStatusSnapshot.TxStatus.INCLUDED);
        assertThat(snapshot.confirmations()).isEqualTo(1);
    }

    @Test
    @DisplayName("getTxStatus: 6 confirmations → FINALIZED")
    void getTxStatus_sixConfirmations_returnsFinalized() {
        given(rpcClient.getTransaction("txhash"))
                .willReturn(new BitcoinRpcClient.BitcoinTxInfo("txhash", 6, false));

        TxStatusSnapshot snapshot = adapter.getTxStatus("txhash");

        assertThat(snapshot.status()).isEqualTo(TxStatusSnapshot.TxStatus.FINALIZED);
    }

    @Test
    @DisplayName("getTxStatus: RPC throws → UNKNOWN")
    void getTxStatus_rpcThrows_returnsUnknown() {
        given(rpcClient.getTransaction("txhash")).willThrow(new RuntimeException("not found"));

        TxStatusSnapshot snapshot = adapter.getTxStatus("txhash");

        assertThat(snapshot.status()).isEqualTo(TxStatusSnapshot.TxStatus.UNKNOWN);
    }

    // ──────────────────────────────────────────────────────────────────────
    // getHeads
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHeads: safeBlock and finalizedBlock are null (Bitcoin has no concept of safe/finalized head)")
    void getHeads_safeAndFinalizedAreNull() {
        given(rpcClient.getBlockCount()).willReturn(800_000L);

        HeadsSnapshot heads = adapter.getHeads();

        assertThat(heads.latestBlock()).isEqualTo(800_000L);
        assertThat(heads.safeBlock()).isNull();
        assertThat(heads.finalizedBlock()).isNull();
        assertThat(heads.timestampMs()).isPositive();
    }
}
