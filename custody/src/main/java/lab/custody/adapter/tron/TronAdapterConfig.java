package lab.custody.adapter.tron;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 20-8: Spring configuration for the TRON adapter.
 *
 * <p>Activated only when {@code custody.tron.enabled=true}.
 * Wires {@link TronRpcClient}, {@link TronSigner}, and {@link TronAdapter} as beans.
 */
@Configuration
@ConditionalOnProperty(name = "custody.tron.enabled", havingValue = "true")
@EnableConfigurationProperties(TronRpcProperties.class)
public class TronAdapterConfig {

    @Bean
    public TronRpcClient tronRpcClient(TronRpcProperties properties) {
        return new TronRpcClient(properties);
    }

    @Bean
    public TronSigner tronSigner(
            @Value("${custody.tron.private-key:}") String hexPrivateKey
    ) {
        if (hexPrivateKey == null || hexPrivateKey.isBlank()) {
            throw new IllegalStateException(
                    "custody.tron.private-key must be set when custody.tron.enabled=true");
        }
        return new TronSigner(hexPrivateKey);
    }

    @Bean
    public TronAdapter tronAdapter(
            TronRpcClient rpcClient,
            TronSigner signer,
            ObjectMapper objectMapper
    ) {
        return new TronAdapter(rpcClient, signer, objectMapper);
    }
}
