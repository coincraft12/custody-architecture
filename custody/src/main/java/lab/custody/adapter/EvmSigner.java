package lab.custody.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

@Component
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "rpc")
public class EvmSigner implements Signer {

    private final Credentials credentials;

    public EvmSigner(@Value("${custody.evm.private-key:}") String privateKey) {
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalStateException("custody.evm.private-key must be configured when custody.chain.mode=rpc");
        }
        this.credentials = Credentials.create(privateKey.trim());
    }

    @Override
    public String sign(RawTransaction tx, long chainId) {
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, credentials);
        return Numeric.toHexString(signed);
    }

    @Override
    public String getAddress() {
        return credentials.getAddress();
    }
}
