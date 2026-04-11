package lab.custody.adapter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lab.custody.domain.withdrawal.ChainType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import lab.custody.adapter.Signer;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "rpc")
public class EvmRpcAdapter implements ChainAdapter {

    private static final Pattern EVM_ADDRESS_PATTERN = Pattern.compile("^0x[a-fA-F0-9]{40}$");
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger DEFAULT_MAX_PRIORITY_FEE_PER_GAS = BigInteger.valueOf(2_000_000_000L);
    private static final BigInteger DEFAULT_MAX_FEE_PER_GAS = BigInteger.valueOf(20_000_000_000L);

    private final Web3j web3j;
    private final long configuredChainId;
    private final Signer signer;
    private final MeterRegistry meterRegistry;

    public EvmRpcAdapter(
            Web3j web3j,
            @Value("${custody.evm.chain-id}") long configuredChainId,
            Signer signer,
            MeterRegistry meterRegistry
    ) {
        this.web3j = web3j;
        this.configuredChainId = configuredChainId;
        this.signer = signer;
        this.meterRegistry = meterRegistry;
    }

    private void recordRpcCall(String method, boolean success, long startNanos) {
        Timer.builder("custody.rpc.call.duration")
                .description("Duration of EVM RPC calls")
                .tag("method", method)
                .tag("success", String.valueOf(success))
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        Counter.builder("custody.rpc.call.total")
                .description("Total number of EVM RPC calls")
                .tag("method", method)
                .tag("success", String.valueOf(success))
                .register(meterRegistry)
                .increment();
    }

    @Override
    // Submit an EVM transfer via RPC using EIP-1559 fields and return the tx hash if accepted by the node.
    // This adapter hides RPC/web3j details so upper layers only deal with BroadcastCommand/BroadcastResult.
    public BroadcastResult broadcast(BroadcastCommand command) {
        if (!isValidAddress(command.to())) {
            throw new IllegalArgumentException("Invalid EVM to-address: " + command.to());
        }

        ensureConnectedChainIdMatchesConfigured();

        long start = System.nanoTime();
        boolean success = false;
        try {
                BigInteger nonce = command.nonce() >= 0
                    ? BigInteger.valueOf(command.nonce())
                    : getPendingNonce(signer.getAddress());
            BigInteger valueWei = BigInteger.valueOf(command.amount());
            BigInteger maxPriorityFeePerGas = command.maxPriorityFeePerGas() != null
                    ? BigInteger.valueOf(command.maxPriorityFeePerGas())
                    : DEFAULT_MAX_PRIORITY_FEE_PER_GAS;
            BigInteger maxFeePerGas = command.maxFeePerGas() != null
                    ? BigInteger.valueOf(command.maxFeePerGas())
                    : DEFAULT_MAX_FEE_PER_GAS;

                RawTransaction rawTransaction = RawTransaction.createEtherTransaction(
                    configuredChainId,
                    nonce,
                    GAS_LIMIT,
                    command.to(),
                    valueWei,
                    maxPriorityFeePerGas,
                    maxFeePerGas
                );

                String signedTxHex = signer.sign(rawTransaction, configuredChainId);

            EthSendTransaction sent = web3j.ethSendRawTransaction(signedTxHex).send();
            if (sent.hasError()) {
                throw new BroadcastRejectedException("EVM RPC rejected transaction: " + sent.getError().getMessage());
            }

            String txHash = sent.getTransactionHash();
            if (txHash == null || txHash.isBlank()) {
                throw new IllegalStateException("RPC returned an empty tx hash");
            }

            success = true;
            return new BroadcastResult(txHash, true);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to execute EVM RPC request", e);
        } finally {
            recordRpcCall("broadcast", success, start);
        }
    }

    // Safety check to avoid sending transactions to the wrong chain when config/RPC endpoint mismatch.
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

    public String getSenderAddress() {
        return signer.getAddress();
    }

    public long getChainId() {
        return configuredChainId;
    }

    // Read the pending nonce (not latest) so new attempts account for in-flight transactions.
    public BigInteger getPendingNonce(String address) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            EthGetTransactionCount txCountResponse = web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send();
            if (txCountResponse.hasError()) {
                throw new IllegalStateException("Failed to fetch nonce from RPC: " + txCountResponse.getError().getMessage());
            }
            success = true;
            return txCountResponse.getTransactionCount();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fetch pending nonce", e);
        } finally {
            recordRpcCall("getPendingNonce", success, start);
        }
    }

    // Receipt lookup is used by sync/confirmation tracking to detect inclusion/finalization progress.
    public Optional<TransactionReceipt> getReceipt(String txHash) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Optional<TransactionReceipt> result = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            success = true;
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fetch receipt", e);
        } finally {
            recordRpcCall("getReceipt", success, start);
        }
    }

    // Transaction lookup is used by demo/wallet endpoints for observability during labs.
    public Optional<org.web3j.protocol.core.methods.response.Transaction> getTransaction(String txHash) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            EthTransaction response = web3j.ethGetTransactionByHash(txHash).send();
            success = true;
            return response.getTransaction();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fetch transaction", e);
        } finally {
            recordRpcCall("getTransaction", success, start);
        }
    }
}
