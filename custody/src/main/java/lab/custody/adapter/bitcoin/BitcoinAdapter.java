package lab.custody.adapter.bitcoin;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterCapability;
import lab.custody.adapter.HeadsSnapshot;
import lab.custody.adapter.SendRequest;
import lab.custody.adapter.TxStatusSnapshot;
import lab.custody.adapter.prepared.BitcoinPreparedTx;
import lab.custody.adapter.prepared.PreparedTx;
import lab.custody.domain.bitcoin.UtxoLock;
import lab.custody.domain.bitcoin.UtxoLockRepository;
import lab.custody.domain.withdrawal.ChainType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 19-9: Bitcoin chain adapter implementing the UTXO-based send lifecycle.
 *
 * <p>Activated only when {@code custody.bitcoin.enabled=true}.
 *
 * <p>UTXO selection uses a greedy largest-first strategy. Selected UTXOs are
 * locked in the {@code utxo_locks} table for the configured duration to prevent
 * double-spend during the broadcast window.
 *
 * <p>Dust threshold: change outputs below 546 sat are absorbed into the fee
 * rather than creating an uneconomical output.
 */
@Component
@ConditionalOnProperty(name = "custody.bitcoin.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class BitcoinAdapter implements ChainAdapter {

    /** P2WPKH output size: 31 bytes. Input size (P2WPKH): ~68 vbytes. Base tx overhead: ~10 vbytes. */
    private static final long DUST_THRESHOLD_SAT = 546L;
    /** Default fee rate used as fallback when estimatesmartfee is unavailable (1 sat/vbyte). */
    private static final double FALLBACK_SAT_PER_VBYTE = 1.0;
    /** Confirmation target for fee estimation (6 blocks ≈ ~60 minutes). */
    private static final int FEE_TARGET_BLOCKS = 6;

    private final BitcoinRpcClient rpcClient;
    private final BitcoinSigner signer;
    private final UtxoLockRepository utxoLockRepository;
    private final BitcoinRpcProperties properties;

    // ──────────────────────────────────────────────────────────────────────
    // ChainAdapter identity
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public ChainType getChainType() {
        return ChainType.BITCOIN;
    }

    @Override
    public Set<ChainAdapterCapability> capabilities() {
        return Set.of(ChainAdapterCapability.UTXO_MODEL, ChainAdapterCapability.REPLACE_TX);
    }

    // ──────────────────────────────────────────────────────────────────────
    // prepareSend
    // ──────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PreparedTx prepareSend(SendRequest request) {
        String fromAddress = request.fromAddress();
        long amountSat = request.amountRaw().longValue();
        String toAddress = request.toAddress();

        log.info("event=bitcoin_adapter.prepare_send from={} to={} amountSat={}", fromAddress, toAddress, amountSat);

        // 1. Fetch available UTXOs
        List<BitcoinRpcClient.Utxo> available = rpcClient.listUnspent(fromAddress);

        // 2. Filter out currently-locked UTXOs
        Set<String> lockedKeys = Set.copyOf(utxoLockRepository.findAllLockedUtxoKeys());
        List<BitcoinRpcClient.Utxo> unlocked = available.stream()
                .filter(u -> !lockedKeys.contains(u.txid() + ":" + u.vout()))
                .toList();

        log.debug("event=bitcoin_adapter.utxo_selection available={} locked_filtered={} unlocked={}",
                available.size(), available.size() - unlocked.size(), unlocked.size());

        // 3. Estimate fee
        long feeSat = estimateFee(unlocked.size());

        // 4. Greedy UTXO selection — largest first
        List<BitcoinRpcClient.Utxo> sorted = new ArrayList<>(unlocked);
        sorted.sort(Comparator.comparingLong(BitcoinRpcClient.Utxo::amountSat).reversed());

        List<BitcoinRpcClient.Utxo> selected = new ArrayList<>();
        long accumulated = 0L;
        long target = amountSat + feeSat;

        for (BitcoinRpcClient.Utxo utxo : sorted) {
            selected.add(utxo);
            accumulated += utxo.amountSat();
            if (accumulated >= target) break;
        }

        if (accumulated < target) {
            throw new InsufficientFundsException(
                    String.format("Insufficient unlocked UTXOs: need %d sat, have %d sat", target, accumulated));
        }

        // 5. Dust check: change < 546 sat → absorb into fee
        long change = accumulated - amountSat - feeSat;
        if (change < DUST_THRESHOLD_SAT) {
            feeSat += change;
            change = 0;
        }

        // 6. Insert UTXO locks
        Instant expiresAt = Instant.now().plusSeconds(properties.utxoLockMinutes() * 60L);
        List<String> lockedUtxoKeys = new ArrayList<>();
        for (BitcoinRpcClient.Utxo utxo : selected) {
            UtxoLock lock = new UtxoLock();
            lock.setTxid(utxo.txid());
            lock.setVout(utxo.vout());
            lock.setAddress(utxo.address());
            lock.setAmountSat(utxo.amountSat());
            lock.setStatus(UtxoLock.UtxoLockStatus.LOCKED);
            lock.setCreatedAt(Instant.now());
            lock.setExpiresAt(expiresAt);
            utxoLockRepository.save(lock);
            lockedUtxoKeys.add(utxo.txid() + ":" + utxo.vout());
        }

        // 7. Build + sign transaction
        String rawHex = signer.signTransaction(
                toAddress,
                amountSat,
                feeSat,
                fromAddress,        // change goes back to the sender address
                change,
                selected
        );

        log.info("event=bitcoin_adapter.prepare_send.done inputs={} feeSat={} changeSat={}", selected.size(), feeSat, change);

        return new BitcoinPreparedTx(rawHex, List.copyOf(lockedUtxoKeys), feeSat);
    }

    // ──────────────────────────────────────────────────────────────────────
    // broadcast
    // ──────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BroadcastResult broadcast(PreparedTx prepared) {
        if (!(prepared instanceof BitcoinPreparedTx btcTx)) {
            throw new IllegalArgumentException(
                    "BitcoinAdapter requires BitcoinPreparedTx, got: " + prepared.getClass().getSimpleName());
        }

        try {
            String txHash = rpcClient.sendRawTransaction(btcTx.rawHex());
            log.info("event=bitcoin_adapter.broadcast.success txHash={}", txHash);
            return new BroadcastResult(txHash, true);
        } catch (Exception e) {
            log.warn("event=bitcoin_adapter.broadcast.failed error={} — releasing {} UTXO locks",
                    e.getMessage(), btcTx.lockedUtxoKeys().size());
            releaseUtxoLocks(btcTx.lockedUtxoKeys());
            throw e;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // getTxStatus
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public TxStatusSnapshot getTxStatus(String txHash) {
        try {
            BitcoinRpcClient.BitcoinTxInfo info = rpcClient.getTransaction(txHash);
            TxStatusSnapshot.TxStatus status;
            if (info.confirmations() == 0) {
                status = TxStatusSnapshot.TxStatus.PENDING;
            } else if (info.confirmations() < 6) {
                status = TxStatusSnapshot.TxStatus.INCLUDED;
            } else {
                status = TxStatusSnapshot.TxStatus.FINALIZED;
            }
            return new TxStatusSnapshot(status, null, info.confirmations(), null);
        } catch (Exception e) {
            log.debug("event=bitcoin_adapter.get_tx_status.unknown txHash={} error={}", txHash, e.getMessage());
            return new TxStatusSnapshot(TxStatusSnapshot.TxStatus.UNKNOWN, null, null, null);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // getHeads
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public HeadsSnapshot getHeads() {
        long blockCount = rpcClient.getBlockCount();
        // Bitcoin has no "safe" or "finalized" head concept (UTXO chain with probabilistic finality)
        return new HeadsSnapshot(blockCount, null, null, System.currentTimeMillis());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Estimate fee in satoshis. Falls back to 1 sat/vbyte if estimation is unavailable.
     * Assumes a typical 1-input P2WPKH transaction (~140 vbytes) as base estimate;
     * the actual size will be close when there are a small number of inputs.
     */
    private long estimateFee(int inputCount) {
        double feeRateBtcPerKb;
        try {
            feeRateBtcPerKb = rpcClient.estimateSmartFee(FEE_TARGET_BLOCKS);
        } catch (Exception e) {
            log.warn("event=bitcoin_adapter.fee_estimation.failed error={} — using fallback", e.getMessage());
            feeRateBtcPerKb = 0.0;
        }

        double satPerVbyte = (feeRateBtcPerKb > 0)
                ? feeRateBtcPerKb * 100_000_000.0 / 1000.0
                : FALLBACK_SAT_PER_VBYTE;

        // Estimate tx vsize: 10 overhead + 68 per input + 31 per output (2 outputs max)
        long estimatedVsize = 10L + (68L * inputCount) + (31L * 2);
        long feeSat = (long) Math.ceil(satPerVbyte * estimatedVsize);
        log.debug("event=bitcoin_adapter.fee_estimation satPerVbyte={} vsize={} feeSat={}", satPerVbyte, estimatedVsize, feeSat);
        return feeSat;
    }

    /** Transition UTXO lock records from LOCKED → RELEASED. */
    private void releaseUtxoLocks(List<String> keys) {
        for (String key : keys) {
            String[] parts = key.split(":");
            if (parts.length != 2) continue;
            String txid = parts[0];
            int vout;
            try {
                vout = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                log.warn("event=bitcoin_adapter.release_lock.invalid_key key={}", key);
                continue;
            }
            utxoLockRepository.findAll().stream()
                    .filter(u -> u.getTxid().equals(txid) && u.getVout() == vout
                            && u.getStatus() == UtxoLock.UtxoLockStatus.LOCKED)
                    .forEach(u -> {
                        u.setStatus(UtxoLock.UtxoLockStatus.RELEASED);
                        utxoLockRepository.save(u);
                    });
        }
    }
}
