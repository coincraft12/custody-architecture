package lab.custody.adapter;

/**
 * 17-2: Optional capabilities that a {@link ChainAdapter} implementation may support.
 *
 * <p>Callers can inspect {@link ChainAdapter#capabilities()} before using
 * capability-specific methods to avoid runtime errors on unsupported chains.
 */
public enum ChainAdapterCapability {
    /** Adapter can return the pending nonce for an account. */
    ACCOUNT_NONCE,
    /** Adapter exposes a finalized-head block number. */
    FINALIZED_HEAD,
    /** Adapter supports fee-bump transaction replacement (EVM RBF / similar). */
    REPLACE_TX,
    /** Chain uses a UTXO model (Bitcoin, etc.). */
    UTXO_MODEL,
    /** Chain supports durable nonces (e.g. Solana durable nonce accounts). */
    DURABLE_NONCE
}
