package lab.custody.adapter;

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
    public Web3j web3j(@Value("${custody.evm.rpc-url}") String rpcUrl) {
        return Web3j.build(new HttpService(rpcUrl));
    }
}
