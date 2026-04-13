package lab.custody.adapter;

import lab.custody.adapter.prepared.BftMockPreparedTx;
import lab.custody.adapter.prepared.PreparedTx;
import lab.custody.domain.withdrawal.ChainType;
import org.springframework.stereotype.Component;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 12-1-1/12-1-2: BFT mock adapter with getTransactionReceipt() and getPendingNonce().
 *
 * <p>BFT chains (e.g. Tendermint-based) differ from EVM:
 * <ul>
 *   <li>Nonces are optional / per-sender sequence numbers (not global)</li>
 *   <li>Transactions are finalized immediately on inclusion (no block confirmations needed)</li>
 *   <li>messageId replaces txHash</li>
 * </ul>
 */
@Component
public class BftMockAdapter implements ChainAdapter {

    // 12-1-2: Per-address nonce counter (simulates sequence number)
    private final Map<String, AtomicLong> nonceMap = new ConcurrentHashMap<>();
    // 12-1-1: In-memory receipt store for test/mock use
    private final Map<String, TransactionReceipt> receiptStore = new ConcurrentHashMap<>();

    @Override
    public BroadcastResult broadcast(BroadcastCommand command) {
        // BFT 특징:
        // - nonce 개념 약함
        // - txHash 대신 messageId 느낌
        String messageId = "BFT_" + UUID.randomUUID().toString().substring(0, 8);

        // 12-1-1: Immediately create a mock receipt — BFT chains finalize on inclusion
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setTransactionHash(messageId);
        receipt.setStatus("0x1");  // success
        receiptStore.put(messageId, receipt);

        // 12-1-2: Increment nonce for from address
        nonceMap.computeIfAbsent(command.from(), k -> new AtomicLong(0)).incrementAndGet();

        return new BroadcastResult(messageId, true);
    }

    /**
     * 12-1-1: BFT transactions are immediately finalized — receipt always present after broadcast.
     */
    @Override
    public Optional<TransactionReceipt> getTransactionReceipt(String txHash) {
        return Optional.ofNullable(receiptStore.get(txHash));
    }

    /**
     * 12-1-2: Returns current sequence number for the address (next expected nonce).
     * Not a ChainAdapter override — BFT nonce concept differs from EVM.
     */
    public long getPendingNonce(String address) {
        AtomicLong counter = nonceMap.get(address);
        return counter != null ? counter.get() : 0L;
    }

    @Override
    public ChainType getChainType() {
        return ChainType.BFT;
    }

    @Override
    public Set<ChainAdapterCapability> capabilities() {
        return Set.of();
    }

    /**
     * 17-7: prepareSend() — BFT mock: no real signing, just capture the from address.
     */
    @Override
    public PreparedTx prepareSend(SendRequest request) {
        return new BftMockPreparedTx(request.fromAddress());
    }

    /**
     * 17-7: broadcast(PreparedTx) — BFT transactions are immediately finalized.
     */
    @Override
    public BroadcastResult broadcast(PreparedTx prepared) {
        String from = (prepared instanceof BftMockPreparedTx bft) ? bft.fromAddress() : "unknown";
        String messageId = "BFT_" + UUID.randomUUID().toString().substring(0, 8);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setTransactionHash(messageId);
        receipt.setStatus("0x1");
        receiptStore.put(messageId, receipt);

        nonceMap.computeIfAbsent(from, k -> new AtomicLong(0)).incrementAndGet();
        return new BroadcastResult(messageId, true);
    }

    @Override
    public TxStatusSnapshot getTxStatus(String txHash) {
        TransactionReceipt receipt = receiptStore.get(txHash);
        if (receipt == null) {
            return new TxStatusSnapshot(TxStatusSnapshot.TxStatus.UNKNOWN, null, null, null);
        }
        boolean success = "0x1".equals(receipt.getStatus());
        TxStatusSnapshot.TxStatus status = success
                ? TxStatusSnapshot.TxStatus.FINALIZED
                : TxStatusSnapshot.TxStatus.FAILED;
        return new TxStatusSnapshot(status, null, null, null);
    }

    @Override
    public HeadsSnapshot getHeads() {
        return new HeadsSnapshot(0L, null, null, System.currentTimeMillis());
    }
}
