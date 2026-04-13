package lab.custody.adapter;

/**
 * 17-2: Snapshot of a chain's latest, safe, and finalized block heights.
 *
 * <p>{@code safeBlock} and {@code finalizedBlock} are nullable because not all chains
 * expose these concepts (e.g. UTXO chains, BFT chains with instant finality).
 */
public record HeadsSnapshot(
        long latestBlock,
        Long safeBlock,
        Long finalizedBlock,
        long timestampMs
) {
}
