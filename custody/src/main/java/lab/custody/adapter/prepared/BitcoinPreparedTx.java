package lab.custody.adapter.prepared;

/**
 * 17-1: Bitcoin prepared transaction stub.
 *
 * <p>{@code signedRawTx} is the fully-signed raw transaction bytes ready to broadcast.
 * {@code changeAddress} is the change output address.
 * {@code feeSat} is the total miner fee in satoshis.
 */
public record BitcoinPreparedTx(
        byte[] signedRawTx,
        String changeAddress,
        long feeSat
) implements PreparedTx {
}
