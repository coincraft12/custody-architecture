package lab.custody.adapter;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "rpc")
public class RpcModeStartupGuard {

    @Value("${custody.evm.chain-id}")
    private long chainId;

    @Value("${custody.evm.private-key:}")
    private String privateKey;

    @Value("${custody.evm.rpc-url:}")
    private String rpcUrl;

    @PostConstruct
    void validate() {
        if (chainId == 1) {
            throw new IllegalStateException("Mainnet(chain-id=1) is not allowed in labs RPC mode");
        }
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalStateException("CUSTODY_EVM_PRIVATE_KEY must be configured in rpc mode");
        }
        if (rpcUrl == null || rpcUrl.isBlank()) {
            throw new IllegalStateException("CUSTODY_EVM_RPC_URL must be configured in rpc mode");
        }
    }
}
