package lab.custody.adapter.prepared;

/**
 * 17-1: TRON prepared transaction stub.
 *
 * <p>{@code signedTxBytes} is the protobuf-serialized, signed transaction bytes.
 * {@code expirationMs} is the epoch-millisecond timestamp at which the transaction expires.
 */
public record TronPreparedTx(
        byte[] signedTxBytes,
        long expirationMs
) implements PreparedTx {
}
