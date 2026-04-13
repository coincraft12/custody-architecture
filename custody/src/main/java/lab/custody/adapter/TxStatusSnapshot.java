package lab.custody.adapter;

/**
 * 17-2: Chain-agnostic snapshot of a transaction's on-chain status.
 *
 * <p>Nullable fields are chain/state specific:
 * <ul>
 *   <li>{@code blockNumber} — null if not yet included in a block</li>
 *   <li>{@code confirmations} — null if block number is unavailable</li>
 *   <li>{@code revertReason} — EVM-specific; null for non-EVM chains or non-failed txs</li>
 * </ul>
 */
public record TxStatusSnapshot(
        TxStatus status,
        Long blockNumber,
        Integer confirmations,
        String revertReason
) {

    public enum TxStatus {
        /** Chain has no record of this transaction. */
        UNKNOWN,
        /** Transaction is in the mempool but not yet included in a block. */
        PENDING,
        /** Transaction has been included in a block. */
        INCLUDED,
        /** Transaction has crossed the chain's "safe" checkpoint. */
        SAFE,
        /** Transaction is considered finalized (irreversible). */
        FINALIZED,
        /** Transaction was included but execution reverted / failed. */
        FAILED,
        /** Transaction was dropped from the mempool. */
        DROPPED,
        /** Transaction was replaced by a higher-fee transaction with the same nonce. */
        REPLACED
    }
}
