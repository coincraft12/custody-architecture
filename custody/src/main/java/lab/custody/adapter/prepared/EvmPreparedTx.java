package lab.custody.adapter.prepared;

import java.math.BigInteger;

/**
 * 17-1: EVM (EIP-1559) prepared transaction.
 *
 * <p>{@code signedHexTx} is the fully signed, RLP-encoded transaction in hex (0x-prefixed)
 * ready to be passed to {@code eth_sendRawTransaction}.
 */
public record EvmPreparedTx(
        String signedHexTx,
        long nonce,
        BigInteger maxFeePerGas,
        BigInteger maxPriorityFeePerGas
) implements PreparedTx {
}
