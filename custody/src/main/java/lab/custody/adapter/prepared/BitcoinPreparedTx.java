package lab.custody.adapter.prepared;

import java.util.List;

/**
 * 19-1: Bitcoin prepared transaction — UTXO model.
 *
 * <p>{@code rawHex} is the fully-signed raw transaction hex ready to broadcast via
 * {@code sendrawtransaction}.
 * {@code lockedUtxoKeys} contains "txid:vout" strings for UTXO lock release on failure.
 * {@code feeSat} is the total miner fee in satoshis.
 */
public record BitcoinPreparedTx(
        String rawHex,
        List<String> lockedUtxoKeys,
        long feeSat
) implements PreparedTx {
}
