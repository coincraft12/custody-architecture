package lab.custody.adapter;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    /**
     * 4-3/4-4: EvmRpcProviderPool 빈 — primary + fallback URL 리스트를 OkHttp 타임아웃 설정과 함께 생성.
     *
     * <p>4-4-1/4-4-2: connect/read 타임아웃을 명시적으로 설정한다 (기본 30s).
     * 4-4-4: 환경변수(CUSTODY_EVM_CONNECT_TIMEOUT_SECONDS, CUSTODY_EVM_READ_TIMEOUT_SECONDS)로 오버라이드 가능.
     */
    @Bean(destroyMethod = "shutdown")
    public EvmRpcProviderPool evmRpcProviderPool(
            @Value("${custody.evm.rpc-url}") String primaryUrl,
            // 4-3-1: fallback-rpc-urls (쉼표 구분 리스트, 기본값 빈 리스트)
            @Value("${custody.evm.fallback-rpc-urls:}") List<String> fallbackUrls,
            // 4-4-1/4-4-4
            @Value("${custody.evm.connect-timeout-seconds:${CUSTODY_EVM_CONNECT_TIMEOUT_SECONDS:30}}") int connectTimeoutSeconds,
            // 4-4-2/4-4-4
            @Value("${custody.evm.read-timeout-seconds:${CUSTODY_EVM_READ_TIMEOUT_SECONDS:30}}") int readTimeoutSeconds,
            @Value("${custody.evm.proxy.enabled:false}") boolean proxyEnabled,
            @Value("${custody.evm.proxy.host:}") String proxyHost,
            @Value("${custody.evm.proxy.port:8080}") int proxyPort,
            @Value("${custody.evm.proxy.username:}") String proxyUsername,
            @Value("${custody.evm.proxy.password:}") String proxyPassword) {

        List<String> allUrls = new ArrayList<>();
        allUrls.add(primaryUrl);
        // 4-3-1: fallback URLs — 빈 문자열은 제외
        fallbackUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .forEach(allUrls::add);

        List<Web3j> instances = allUrls.stream()
                .map(url -> buildWeb3j(url, connectTimeoutSeconds, readTimeoutSeconds,
                        proxyEnabled, proxyHost, proxyPort, proxyUsername, proxyPassword))
                .toList();

        return new EvmRpcProviderPool(allUrls, instances);
    }

    /**
     * 4-4: OkHttpClient에 connect/read 타임아웃을 명시적으로 설정한 Web3j 인스턴스 생성.
     */
    private static Web3j buildWeb3j(
            String url,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            boolean proxyEnabled,
            String proxyHost,
            int proxyPort,
            String proxyUsername,
            String proxyPassword) {

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS);

        if (proxyEnabled) {
            if (proxyHost == null || proxyHost.isBlank()) {
                throw new IllegalStateException(
                        "custody.evm.proxy.host must be configured when custody.evm.proxy.enabled=true");
            }
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            clientBuilder.proxy(proxy);

            if (proxyUsername != null && !proxyUsername.isBlank()) {
                clientBuilder.proxyAuthenticator((Route route, Response response) -> {
                    String credential = okhttp3.Credentials.basic(
                            proxyUsername, proxyPassword == null ? "" : proxyPassword);
                    Request request = response.request();
                    return request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                });
            }
        }

        return Web3j.build(new HttpService(url, clientBuilder.build(), false));
    }
}
