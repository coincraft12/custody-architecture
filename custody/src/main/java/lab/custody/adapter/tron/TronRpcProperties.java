package lab.custody.adapter.tron;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 20-2: Configuration properties for the TRON HTTP REST API connection.
 *
 * <p>All properties are bound from the {@code custody.tron} prefix.
 * Compact constructor sets the Nile testnet as the default RPC URL.
 */
@ConfigurationProperties(prefix = "custody.tron")
public record TronRpcProperties(
        String rpcUrl,   // TRON Full Node HTTP API base URL
        String apiKey    // TronGrid TRON-PRO-API-KEY (nullable)
) {
    public TronRpcProperties {
        if (rpcUrl == null || rpcUrl.isBlank()) {
            rpcUrl = "https://api.nileex.io"; // Nile testnet default
        }
    }
}
