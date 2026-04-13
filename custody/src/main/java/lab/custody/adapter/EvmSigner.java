package lab.custody.adapter;

import java.util.Arrays;

import lab.custody.domain.withdrawal.ChainType;
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
        // 2-1-4: best-effort zeroing — @Value delivers a String (immutable, cannot be zeroed),
        // so we copy to char[] to limit the mutable key material lifetime, then zero immediately.
        // Full zeroing is only possible with KMS/HSM (see Signer interface KMS plan).
        char[] keyChars = privateKey.toCharArray();
        try {
            this.credentials = Credentials.create(new String(keyChars).trim());
        } finally {
            Arrays.fill(keyChars, '\0');
        }
    }

    /**
     * 레거시 EVM 서명 — web3j RawTransaction을 직접 받는다.
     *
     * @deprecated 새 코드는 {@link #signRaw(byte[])} 를 사용할 것.
     */
    @Deprecated
    @Override
    public String sign(RawTransaction tx, long chainId) {
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, credentials);
        return Numeric.toHexString(signed);
    }

    /**
     * 17-4: chain-agnostic signRaw().
     *
     * <p>Signs the keccak256 hash of {@code txBytes} and returns 65 bytes (v‖r‖s).
     * For full EVM transaction signing (where the RawTransaction object is available),
     * callers should use the {@link #sign(RawTransaction, long)} path or access
     * credentials directly via {@link #getCredentials()}.
     */
    @Override
    public byte[] signRaw(byte[] txBytes) {
        org.web3j.crypto.Sign.SignatureData sig =
                org.web3j.crypto.Sign.signMessage(txBytes, credentials.getEcKeyPair());
        byte[] result = new byte[65];
        result[0] = sig.getV()[0];
        System.arraycopy(sig.getR(), 0, result, 1, 32);
        System.arraycopy(sig.getS(), 0, result, 33, 32);
        return result;
    }

    @Override
    public String getAddress() {
        return credentials.getAddress();
    }

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }

    /**
     * Package-private: allows {@link EvmRpcAdapter#prepareSend} to call
     * {@code TransactionEncoder.signMessage(rawTx, chainId, credentials)} directly,
     * preserving all EIP-1559 fields without reconstructing from bytes.
     */
    Credentials getCredentials() {
        return credentials;
    }
}
