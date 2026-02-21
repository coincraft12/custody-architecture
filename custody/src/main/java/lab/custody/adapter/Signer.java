package lab.custody.adapter;

import org.web3j.crypto.RawTransaction;

public interface Signer {
    String sign(RawTransaction tx, long chainId);
    String getAddress();
}
