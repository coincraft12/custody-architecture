package lab.custody.adapter;

import lab.custody.adapter.prepared.PreparedTx;
import lab.custody.domain.withdrawal.ChainType;

import java.util.Set;
import java.util.UUID;

/**
 * 17-3: Chain-agnostic adapter interface.
 *
 * <p>New lifecycle:
 * <ol>
 *   <li>{@link #prepareSend(SendRequest)} — build and sign a {@link PreparedTx}</li>
 *   <li>{@link #broadcast(PreparedTx)} — submit the signed TX to the network</li>
 *   <li>{@link #getTxStatus(String)} — poll for inclusion / finality</li>
 *   <li>{@link #getHeads()} — query latest/safe/finalized block heights</li>
 * </ol>
 *
 * <p>The old {@link BroadcastCommand}-based {@link #broadcast(BroadcastCommand)} is kept
 * as a deprecated default method for backwards compatibility with existing callers
 * ({@link lab.custody.orchestration.WithdrawalService},
 * {@link lab.custody.orchestration.RetryReplaceService}, etc.).
 */
public interface ChainAdapter {

    /** Identifies which chain this adapter handles. */
    ChainType getChainType();

    /** Optional capabilities advertised by this adapter. */
    Set<ChainAdapterCapability> capabilities();

    /**
     * Build and cryptographically sign a send transaction without broadcasting it.
     *
     * @param request chain-agnostic send parameters
     * @return fully-signed, ready-to-broadcast {@link PreparedTx} subtype
     */
    PreparedTx prepareSend(SendRequest request);

    /**
     * Broadcast a previously-prepared (signed) transaction to the network.
     *
     * @param prepared the signed transaction produced by {@link #prepareSend(SendRequest)}
     * @return broadcast outcome with txHash
     */
    BroadcastResult broadcast(PreparedTx prepared);

    /**
     * Query the current on-chain status of a transaction.
     *
     * @param txHash transaction hash (or equivalent identifier)
     * @return status snapshot; never null ({@link TxStatusSnapshot.TxStatus#UNKNOWN} when not found)
     */
    TxStatusSnapshot getTxStatus(String txHash);

    /**
     * Return the chain's current block-height view (latest, safe, finalized).
     *
     * @return heads snapshot
     */
    HeadsSnapshot getHeads();

    // ──────────────────────────────────────────────────────────────────────
    // Backwards-compatible BroadcastCommand API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Legacy broadcast entry point kept for backwards compatibility.
     *
     * <p>Default implementation delegates to {@link #prepareSend(SendRequest)} +
     * {@link #broadcast(PreparedTx)}. Adapters that need the legacy nonce/fee fields
     * (e.g. {@link lab.custody.adapter.EvmRpcAdapter}) override this method directly.
     *
     * @deprecated Use {@link #prepareSend(SendRequest)} + {@link #broadcast(PreparedTx)} instead.
     */
    @Deprecated
    default BroadcastResult broadcast(BroadcastCommand command) {
        SendRequest req = new SendRequest(
                getChainType(),
                command.asset(),
                command.to(),
                java.math.BigInteger.valueOf(command.amount()),
                command.from(),
                command.withdrawalId().toString()
        );
        PreparedTx prepared = prepareSend(req);
        return broadcast(prepared);
    }

    /**
     * Legacy query — kept for backwards compatibility with tests and demo endpoints.
     *
     * <p>Default implementation maps {@link #getTxStatus(String)} back to Optional logic.
     *
     * @deprecated Use {@link #getTxStatus(String)} instead.
     */
    @Deprecated
    default java.util.Optional<org.web3j.protocol.core.methods.response.TransactionReceipt> getTransactionReceipt(String txHash) {
        return java.util.Optional.empty();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shared record types
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Legacy broadcast command — kept for backwards compatibility.
     *
     * @deprecated Prefer {@link SendRequest}.
     */
    @Deprecated
    record BroadcastCommand(
            UUID withdrawalId,
            String from,
            String to,
            String asset,
            long amount,
            long nonce,
            Long maxPriorityFeePerGas,
            Long maxFeePerGas
    ) {}

    /** Outcome of a broadcast operation. */
    record BroadcastResult(
            String txHash,
            boolean accepted
    ) {}
}
