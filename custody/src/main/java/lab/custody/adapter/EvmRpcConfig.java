package lab.custody.adapter;

import java.net.InetSocketAddress;
import java.net.Proxy;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

@Configuration
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "rpc")
public class EvmRpcConfig {

    @Bean(destroyMethod = "shutdown")
    public Web3j web3j(
            @Value("${custody.evm.rpc-url}") String rpcUrl,
            @Value("${custody.evm.proxy.enabled:false}") boolean proxyEnabled,
            @Value("${custody.evm.proxy.host:}") String proxyHost,
            @Value("${custody.evm.proxy.port:8080}") int proxyPort,
            @Value("${custody.evm.proxy.username:}") String proxyUsername,
            @Value("${custody.evm.proxy.password:}") String proxyPassword) {
        if (!proxyEnabled) {
            return Web3j.build(new HttpService(rpcUrl));
        }

        if (proxyHost == null || proxyHost.isBlank()) {
            throw new IllegalStateException("custody.evm.proxy.host must be configured when custody.evm.proxy.enabled=true");
        }

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder().proxy(proxy);

        if (proxyUsername != null && !proxyUsername.isBlank()) {
            clientBuilder.proxyAuthenticator((Route route, Response response) -> {
                String credential = okhttp3.Credentials.basic(proxyUsername, proxyPassword == null ? "" : proxyPassword);
                Request request = response.request();
                return request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build();
            });
        }

        OkHttpClient client = clientBuilder.build();
        return Web3j.build(new HttpService(rpcUrl, client, false));
    }
}
