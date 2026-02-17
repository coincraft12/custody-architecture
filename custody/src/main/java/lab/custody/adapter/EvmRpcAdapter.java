package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "rpc")
public class EvmRpcAdapter implements ChainAdapter {

    private static final Pattern EVM_ADDRESS_PATTERN = Pattern.compile("^0x[a-fA-F0-9]{40}$");
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger MAX_PRIORITY_FEE_PER_GAS = BigInteger.valueOf(2_000_000_000L);
    private static final BigInteger MAX_FEE_PER_GAS = BigInteger.valueOf(20_000_000_000L);

    private final Web3j web3j;
    private final long configuredChainId;
    private final Credentials credentials;

    public EvmRpcAdapter(
            Web3j web3j,
            @Value("${custody.evm.chain-id}") long configuredChainId,
            @Value("${custody.evm.private-key:}") String privateKey
    ) {
        this.web3j = web3j;
        this.configuredChainId = configuredChainId;
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalStateException("custody.evm.private-key must be configured when custody.chain.mode=rpc");
        }
        this.credentials = Credentials.create(privateKey.trim());
    }

    @Override
    public BroadcastResult broadcast(BroadcastCommand command) {
        if (!isValidAddress(command.to())) {
            throw new IllegalArgumentException("Invalid EVM to-address: " + command.to());
        }

        ensureConnectedChainIdMatchesConfigured();

        try {
            EthGetTransactionCount txCountResponse = web3j.ethGetTransactionCount(
                    credentials.getAddress(),
                    DefaultBlockParameterName.PENDING
            ).send();
            if (txCountResponse.hasError()) {
                throw new IllegalStateException("Failed to fetch nonce from RPC: " + txCountResponse.getError().getMessage());
            }

            BigInteger nonce = txCountResponse.getTransactionCount();
            BigInteger valueWei = BigInteger.valueOf(command.amount());

            RawTransaction rawTransaction = RawTransaction.createEtherTransaction(
                    configuredChainId,
                    nonce,
                    GAS_LIMIT,
                    command.to(),
                    valueWei,
                    MAX_PRIORITY_FEE_PER_GAS,
                    MAX_FEE_PER_GAS
            );

            byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, configuredChainId, credentials);
            String signedTxHex = Numeric.toHexString(signedMessage);

            EthSendTransaction sent = web3j.ethSendRawTransaction(signedTxHex).send();
            if (sent.hasError()) {
                throw new IllegalStateException("Failed to broadcast transaction: " + sent.getError().getMessage());
            }

            String txHash = sent.getTransactionHash();
            if (txHash == null || txHash.isBlank()) {
                throw new IllegalStateException("RPC returned an empty tx hash");
            }

            return new BroadcastResult(txHash, true);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to execute EVM RPC request", e);
        }
    }

    private void ensureConnectedChainIdMatchesConfigured() {
        try {
            EthChainId chainIdResponse = web3j.ethChainId().send();
            if (chainIdResponse.hasError()) {
                throw new IllegalStateException("Failed to verify chain id from RPC: " + chainIdResponse.getError().getMessage());
            }
            long remoteChainId = chainIdResponse.getChainId().longValue();
            if (remoteChainId != configuredChainId) {
                throw new IllegalStateException("Connected RPC chain id mismatch. expected=" + configuredChainId + ", actual=" + remoteChainId);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to verify chain id from RPC", e);
        }
    }

    private static boolean isValidAddress(String address) {
        return address != null && EVM_ADDRESS_PATTERN.matcher(address).matches();
    }

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }
}
