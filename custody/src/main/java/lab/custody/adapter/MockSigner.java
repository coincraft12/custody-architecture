package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.RawTransaction;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockSigner implements Signer {

    @Deprecated
    @Override
    public String sign(RawTransaction tx, long chainId) {
        return "0xMOCK_SIGNED_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 17-4: Mock signRaw() — returns 65 zero bytes (EVM-style placeholder).
     */
    @Override
    public byte[] signRaw(byte[] txBytes) {
        return new byte[65];
    }

    @Override
    public String getAddress() {
        return "0xMOCK_SENDER";
    }

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }
}
