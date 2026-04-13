package lab.custody.adapter.bitcoin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 19-7: Configuration properties for the Bitcoin JSON-RPC connection.
 *
 * <p>All properties are bound from the {@code custody.bitcoin} prefix.
 * Compact constructor provides defaults for optional fields.
 */
@ConfigurationProperties(prefix = "custody.bitcoin")
public record BitcoinRpcProperties(
        String rpcUrl,
        String rpcUser,
        String rpcPassword,
        String network,
        int utxoLockMinutes
) {
    public BitcoinRpcProperties {
        if (rpcUrl == null) rpcUrl = "http://localhost:18443"; // regtest default
        if (network == null) network = "regtest";
        if (utxoLockMinutes == 0) utxoLockMinutes = 10;
    }
}
