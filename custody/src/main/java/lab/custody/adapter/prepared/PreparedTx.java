package lab.custody.adapter.prepared;

/**
 * 17-1: Chain-agnostic sealed interface for a fully-prepared (signed) transaction.
 *
 * <p>Each chain type has its own record implementing this interface, carrying
 * the exact fields needed to broadcast to that chain's network.
 */
public sealed interface PreparedTx
        permits EvmPreparedTx, BitcoinPreparedTx, TronPreparedTx, SolanaPreparedTx,
                BftMockPreparedTx, EvmMockPreparedTx {
}
