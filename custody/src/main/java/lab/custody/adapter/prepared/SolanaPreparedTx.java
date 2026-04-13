package lab.custody.adapter.prepared;

/**
 * 17-1: Solana prepared transaction stub.
 *
 * <p>{@code serializedTx} is the fully-signed, serialized transaction bytes
 * ready to be passed to {@code sendTransaction}.
 * {@code nonceAccountPubkey} is the durable nonce account public key (if used).
 */
public record SolanaPreparedTx(
        byte[] serializedTx,
        String nonceAccountPubkey
) implements PreparedTx {
}
