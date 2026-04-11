package lab.custody.adapter;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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
import org.web3j.protocol.core.methods.response.EthBlockNumber;
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
    // 4-1-2: Circuit Breaker — 실패율 50% 초과 시 open, 30s 대기 후 half-open.
    // 4-2-3: broadcast()는 @Retry 제외 — eth_sendRawTransaction 재전송 시 멱등성 파괴 위험
    //        (nonce 충돌 또는 이중 전송 발생 가능). nonce-too-low 복구는 WithdrawalService에서 별도 처리.
    @CircuitBreaker(name = "evmRpc", fallbackMethod = "broadcastFallback")
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

    // 4-1-3: Circuit Breaker + 4-2-1: Retry — getPendingNonce는 재시도 안전(멱등).
    @CircuitBreaker(name = "evmRpc", fallbackMethod = "getPendingNonceFallback")
    @Retry(name = "evmRpcRetry")
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

    // 4-2-1: Retry — getReceipt는 재시도 안전(멱등).
    @Retry(name = "evmRpcRetry")
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

    // 4-2-1: Retry — getBlockNumber는 재시도 안전(멱등).
    @Retry(name = "evmRpcRetry")
    // 5-2-2: 현재 블록 번호 조회 — ConfirmationTracker 확정(finalization) 블록 수 확인에 사용.
    public long getBlockNumber() {
        long start = System.nanoTime();
        boolean success = false;
        try {
            EthBlockNumber response = web3j.ethBlockNumber().send();
            if (response.hasError()) {
                throw new IllegalStateException("Failed to fetch block number: " + response.getError().getMessage());
            }
            success = true;
            return response.getBlockNumber().longValue();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fetch block number", e);
        } finally {
            recordRpcCall("getBlockNumber", success, start);
        }
    }

    // 4-2-1: Retry — getTransaction는 재시도 안전(멱등).
    @Retry(name = "evmRpcRetry")
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

    // ─────────────── 4-1: Circuit Breaker fallback methods ───────────────

    /**
     * 4-1-5: Circuit Breaker가 OPEN 상태일 때 broadcast() 대신 호출되는 fallback.
     * BroadcastRejectedException을 던져 WithdrawalService의 nonce 해제 + 상태 전이 로직으로 흐름.
     */
    @SuppressWarnings("unused")
    private BroadcastResult broadcastFallback(BroadcastCommand command, Throwable t) {
        throw new BroadcastRejectedException(
                "EVM RPC circuit breaker is open — broadcast rejected: " + t.getMessage());
    }

    /**
     * 4-1-5: Circuit Breaker가 OPEN 상태일 때 getPendingNonce() fallback.
     */
    @SuppressWarnings("unused")
    private BigInteger getPendingNonceFallback(String address, Throwable t) {
        throw new IllegalStateException(
                "EVM RPC circuit breaker is open — getPendingNonce rejected: " + t.getMessage());
    }
}
