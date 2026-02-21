package lab.custody.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.RawTransaction;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockSigner implements Signer {

    @Override
    public String sign(RawTransaction tx, long chainId) {
        return "0xMOCK_SIGNED_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public String getAddress() {
        return "0xMOCK_SENDER";
    }
}
