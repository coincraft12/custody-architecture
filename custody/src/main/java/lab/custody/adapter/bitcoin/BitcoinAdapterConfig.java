package lab.custody.adapter.bitcoin;

import lab.custody.domain.bitcoin.UtxoLockRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * 19-7/19-8: Spring configuration for the Bitcoin adapter.
 *
 * <p>Activated only when {@code custody.bitcoin.enabled=true}.
 * Wires {@link BitcoinRpcClient} and {@link BitcoinSigner} as beans so they
 * can be injected into {@link BitcoinAdapter}.
 */
@Configuration
@ConditionalOnProperty(name = "custody.bitcoin.enabled", havingValue = "true")
@EnableConfigurationProperties(BitcoinRpcProperties.class)
public class BitcoinAdapterConfig {

    @Bean
    public BitcoinRpcClient bitcoinRpcClient(BitcoinRpcProperties properties) {
        return new BitcoinRpcClient(properties);
    }

    @Bean
    public BitcoinSigner bitcoinSigner(
            BitcoinRpcProperties properties,
            @Value("${custody.bitcoin.private-key:}") String wifPrivateKey
    ) {
        if (wifPrivateKey == null || wifPrivateKey.isBlank()) {
            throw new IllegalStateException(
                    "custody.bitcoin.private-key must be set when custody.bitcoin.enabled=true");
        }
        return new BitcoinSigner(wifPrivateKey, properties.network());
    }

    @Bean
    public BitcoinAdapter bitcoinAdapter(
            BitcoinRpcClient rpcClient,
            BitcoinSigner signer,
            UtxoLockRepository utxoLockRepository,
            BitcoinRpcProperties properties
    ) {
        return new BitcoinAdapter(rpcClient, signer, utxoLockRepository, properties);
    }
}
