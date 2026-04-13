package lab.custody.adapter;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lab.custody.domain.withdrawal.ChainType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthFeeHistory;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import lab.custody.adapter.prepared.PreparedTx;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "rpc")
@Slf4j
public class EvmRpcAdapter implements ChainAdapter {

    private static final Pattern EVM_ADDRESS_PATTERN = Pattern.compile("^0x[a-fA-F0-9]{40}$");
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    // 11-1-5: Fallback defaults — used when RPC gas price fetch fails
    private static final BigInteger DEFAULT_MAX_PRIORITY_FEE_PER_GAS = BigInteger.valueOf(2_000_000_000L);
    private static final BigInteger DEFAULT_MAX_FEE_PER_GAS = BigInteger.valueOf(20_000_000_000L);
    // 11-1-5: Gas price cache TTL — 12 seconds (roughly 1 Ethereum block)
    private static final long GAS_PRICE_CACHE_TTL_NS = TimeUnit.SECONDS.toNanos(12);

    // 11-1-5: Cached gas prices — AtomicReference for lock-free, thread-safe read/write
    private record GasPriceCache(BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas, long timestampNs) {}
    private final AtomicReference<GasPriceCache> gasPriceCache = new AtomicReference<>();

    // 4-3: EvmRpcProviderPool — primary + fallback providers
    private final EvmRpcProviderPool providerPool;
    private final long configuredChainId;
    private final Signer signer;
    private final MeterRegistry meterRegistry;
    private final int feeBumpPercentage;

    public EvmRpcAdapter(
            EvmRpcProviderPool providerPool,
            @Value("${custody.evm.chain-id}") long configuredChainId,
            Signer signer,
            MeterRegistry meterRegistry,
            // 11-2-2: fee-bump-percentage — default 110% (EIP-1559 minimum +10%)
            @Value("${custody.evm.fee-bump-percentage:110}") int feeBumpPercentage
    ) {
        this.providerPool = providerPool;
        this.configuredChainId = configuredChainId;
        this.signer = signer;
        this.meterRegistry = meterRegistry;
        this.feeBumpPercentage = feeBumpPercentage;
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

    /**
     * 4-3-3/4-3-4: Round-Robin provider fallback — 첫 번째 프로바이더 실패 시 다음 순서로 자동 전환.
     * 4-3-5: 각 프로바이더 시도 시 URL을 로그에 기록.
     * broadcast()처럼 멱등하지 않은 작업에는 사용 금지.
     */
    @FunctionalInterface
    private interface ProviderOperation<T> {
        T execute(Web3j web3j) throws IOException;
    }

    private <T> T withFallback(String methodName, ProviderOperation<T> operation) {
        Exception lastError = null;
        for (int i = 0; i < providerPool.size(); i++) {
            Web3j web3j = providerPool.get(i);
            String url = providerPool.getUrl(i);
            try {
                T result = operation.execute(web3j);
                if (i > 0) {
                    // 4-3-5: fallback 성공 시 어떤 URL이 응답했는지 기록
                    log.info("event=rpc_provider.fallback_success method={} provider_index={} url={}",
                            methodName, i, url);
                }
                return result;
            } catch (Exception e) {
                log.warn("event=rpc_provider.failed method={} provider_index={} url={} error={}",
                        methodName, i, url, e.getMessage());
                lastError = e;
            }
        }
        if (lastError instanceof RuntimeException re) throw re;
        throw new IllegalStateException("All " + providerPool.size() + " RPC providers failed for " + methodName, lastError);
    }

    @Override
    // 4-1-2: Circuit Breaker — 실패율 50% 초과 시 open, 30s 대기 후 half-open.
    // 4-2-3: broadcast()는 @Retry 제외 — eth_sendRawTransaction 재전송 시 멱등성 파괴 위험
    //        (nonce 충돌 또는 이중 전송 발생 가능). nonce-too-low 복구는 WithdrawalService에서 별도 처리.
    // 4-3: broadcast()는 primary 프로바이더만 사용 — fallback 시 이중 전송 위험.
    @CircuitBreaker(name = "evmRpc", fallbackMethod = "broadcastFallback")
    public BroadcastResult broadcast(BroadcastCommand command) {
        if (!isValidAddress(command.to())) {
            throw new IllegalArgumentException("Invalid EVM to-address: " + command.to());
        }

        ensureConnectedChainIdMatchesConfigured();

        // 4-3-5: 사용 중인 RPC URL 기록
        log.debug("event=rpc.broadcast.using_provider url={}", providerPool.primaryUrl());

        long start = System.nanoTime();
        boolean success = false;
        try {
            Web3j web3j = providerPool.primary();
            BigInteger nonce = command.nonce() >= 0
                    ? BigInteger.valueOf(command.nonce())
                    : getPendingNonce(signer.getAddress());
            BigInteger valueWei = BigInteger.valueOf(command.amount());
            // 11-1-3/11-1-4: Use dynamic gas pricing from oracle; fall back to defaults on failure
            BigInteger maxPriorityFeePerGas;
            BigInteger maxFeePerGas;
            if (command.maxPriorityFeePerGas() != null && command.maxFeePerGas() != null) {
                // Caller-supplied fees (e.g. replace/fee-bump path)
                maxPriorityFeePerGas = BigInteger.valueOf(command.maxPriorityFeePerGas());
                maxFeePerGas = BigInteger.valueOf(command.maxFeePerGas());
            } else {
                // 11-1-4: Dynamic gas: fetch from oracle (cached, TTL 12s)
                GasPriceCache prices = fetchGasPrices();
                maxPriorityFeePerGas = prices.maxPriorityFeePerGas();
                maxFeePerGas = prices.maxFeePerGas();
            }

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
            EthChainId chainIdResponse = providerPool.primary().ethChainId().send();
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
    // 4-3: withFallback()으로 provider 폴백 지원.
    @CircuitBreaker(name = "evmRpc", fallbackMethod = "getPendingNonceFallback")
    @Retry(name = "evmRpcRetry")
    // Read the pending nonce (not latest) so new attempts account for in-flight transactions.
    public BigInteger getPendingNonce(String address) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            BigInteger result = withFallback("getPendingNonce", web3j -> {
                EthGetTransactionCount txCountResponse =
                        web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send();
                if (txCountResponse.hasError()) {
                    throw new IllegalStateException("Failed to fetch nonce from RPC: " + txCountResponse.getError().getMessage());
                }
                return txCountResponse.getTransactionCount();
            });
            success = true;
            return result;
        } finally {
            recordRpcCall("getPendingNonce", success, start);
        }
    }

    /**
     * 12-1-3: ChainAdapter interface implementation — delegates to getReceipt().
     */
    @Override
    public Optional<TransactionReceipt> getTransactionReceipt(String txHash) {
        return getReceipt(txHash);
    }

    // 4-2-1: Retry — getReceipt는 재시도 안전(멱등).
    // 4-3: withFallback()으로 provider 폴백 지원.
    @Retry(name = "evmRpcRetry")
    // Receipt lookup is used by sync/confirmation tracking to detect inclusion/finalization progress.
    public Optional<TransactionReceipt> getReceipt(String txHash) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Optional<TransactionReceipt> result = withFallback("getReceipt",
                    web3j -> web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt());
            success = true;
            return result;
        } finally {
            recordRpcCall("getReceipt", success, start);
        }
    }

    // 4-2-1: Retry — getBlockNumber는 재시도 안전(멱등).
    // 4-3: withFallback()으로 provider 폴백 지원.
    @Retry(name = "evmRpcRetry")
    // 5-2-2: 현재 블록 번호 조회 — ConfirmationTracker 확정(finalization) 블록 수 확인에 사용.
    public long getBlockNumber() {
        long start = System.nanoTime();
        boolean success = false;
        try {
            long result = withFallback("getBlockNumber", web3j -> {
                EthBlockNumber response = web3j.ethBlockNumber().send();
                if (response.hasError()) {
                    throw new IllegalStateException("Failed to fetch block number: " + response.getError().getMessage());
                }
                return response.getBlockNumber().longValue();
            });
            success = true;
            return result;
        } finally {
            recordRpcCall("getBlockNumber", success, start);
        }
    }

    // 4-2-1: Retry — getTransaction는 재시도 안전(멱등).
    // 4-3: withFallback()으로 provider 폴백 지원.
    @Retry(name = "evmRpcRetry")
    // Transaction lookup is used by demo/wallet endpoints for observability during labs.
    public Optional<org.web3j.protocol.core.methods.response.Transaction> getTransaction(String txHash) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Optional<org.web3j.protocol.core.methods.response.Transaction> result =
                    withFallback("getTransaction",
                            web3j -> web3j.ethGetTransactionByHash(txHash).send().getTransaction());
            success = true;
            return result;
        } finally {
            recordRpcCall("getTransaction", success, start);
        }
    }

    // ─────────────── 11-1: Gas Price Oracle ───────────────

    /**
     * 11-1-1: eth_getBlockByNumber("latest") 호출로 최신 baseFee 조회.
     * EIP-1559 블록에서는 baseFeePerGas 필드가 존재한다.
     * 실패하면 null 반환 — 호출자가 fallback 처리.
     */
    @Retry(name = "evmRpcRetry")
    public BigInteger getLatestBaseFee() {
        long start = System.nanoTime();
        boolean success = false;
        try {
            BigInteger result = withFallback("getLatestBaseFee", web3j -> {
                EthBlock response = web3j.ethGetBlockByNumber(
                        DefaultBlockParameterName.LATEST, false).send();
                if (response.hasError()) {
                    throw new IllegalStateException("Failed to fetch latest block: " + response.getError().getMessage());
                }
                EthBlock.Block block = response.getBlock();
                if (block == null || block.getBaseFeePerGas() == null) {
                    return null;  // pre-EIP-1559 block
                }
                return block.getBaseFeePerGas();
            });
            success = result != null;
            return result;
        } finally {
            recordRpcCall("getLatestBaseFee", success, start);
        }
    }

    /**
     * 11-1-2: eth_feeHistory 호출로 최근 N블록의 reward percentile 조회.
     * 반환값: 각 블록의 [percentile]번째 priority fee 배열.
     * percentile 0~100 사이의 값을 권장한다 (예: 10 = 10th percentile).
     */
    @Retry(name = "evmRpcRetry")
    public List<BigInteger> getFeeHistory(int blocks, double percentile) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            List<BigInteger> result = withFallback("getFeeHistory", web3j -> {
                EthFeeHistory response = web3j.ethFeeHistory(
                        blocks,
                        DefaultBlockParameterName.LATEST,
                        Arrays.asList(percentile)).send();
                if (response.hasError()) {
                    throw new IllegalStateException("eth_feeHistory failed: " + response.getError().getMessage());
                }
                EthFeeHistory.FeeHistory feeHistory = response.getFeeHistory();
                if (feeHistory == null || feeHistory.getReward() == null || feeHistory.getReward().isEmpty()) {
                    return List.of();
                }
                // Each block's reward array has one entry per requested percentile
                // getReward() returns List<List<BigInteger>> (already decoded by web3j)
                return feeHistory.getReward().stream()
                        .filter(r -> r != null && !r.isEmpty())
                        .map(r -> r.get(0))  // first (only) percentile entry per block
                        .map(fee -> fee != null ? fee : BigInteger.ZERO)
                        .toList();
            });
            success = true;
            return result;
        } finally {
            recordRpcCall("getFeeHistory", success, start);
        }
    }

    /**
     * 11-1-4/11-1-5: Dynamic gas price calculation with 12s cache.
     *   maxPriorityFeePerGas = median(feeHistory 10th percentile rewards)
     *   maxFeePerGas         = baseFee * 2 + maxPriorityFeePerGas
     *
     * On any RPC failure, falls back to DEFAULT constants.
     */
    GasPriceCache fetchGasPrices() {
        GasPriceCache cached = gasPriceCache.get();
        if (cached != null && (System.nanoTime() - cached.timestampNs()) < GAS_PRICE_CACHE_TTL_NS) {
            log.debug("event=gas_price_oracle.cache_hit");
            return cached;
        }
        try {
            BigInteger baseFee = getLatestBaseFee();
            if (baseFee == null) {
                log.warn("event=gas_price_oracle.base_fee_null — pre-EIP-1559 block, using defaults");
                return defaultGasPrices();
            }

            List<BigInteger> rewards = getFeeHistory(5, 10.0);
            BigInteger priorityFee;
            if (rewards.isEmpty()) {
                priorityFee = DEFAULT_MAX_PRIORITY_FEE_PER_GAS;
            } else {
                // Use median of recent rewards
                List<BigInteger> sorted = rewards.stream().sorted().toList();
                priorityFee = sorted.get(sorted.size() / 2);
                // Floor: never below 1 wei
                if (priorityFee.compareTo(BigInteger.ONE) < 0) {
                    priorityFee = DEFAULT_MAX_PRIORITY_FEE_PER_GAS;
                }
            }
            // maxFeePerGas = baseFee * 2 + priorityFee (EIP-1559 recommended formula)
            BigInteger maxFee = baseFee.multiply(BigInteger.TWO).add(priorityFee);

            GasPriceCache fresh = new GasPriceCache(priorityFee, maxFee, System.nanoTime());
            gasPriceCache.set(fresh);
            log.info("event=gas_price_oracle.fetched baseFee={} priorityFee={} maxFee={}",
                    baseFee, priorityFee, maxFee);
            return fresh;
        } catch (Exception e) {
            log.warn("event=gas_price_oracle.fetch_failed error={} — using defaults", e.getMessage());
            return defaultGasPrices();
        }
    }

    private GasPriceCache defaultGasPrices() {
        return new GasPriceCache(DEFAULT_MAX_PRIORITY_FEE_PER_GAS, DEFAULT_MAX_FEE_PER_GAS, 0L);
    }

    /**
     * 11-2-2: feeBumpPercentage 기반 수수료 범프.
     * EIP-1559 최소 요구사항: +10% (기본값 110%).
     */
    public BigInteger bumpFee(BigInteger current) {
        if (current == null) return DEFAULT_MAX_PRIORITY_FEE_PER_GAS;
        BigDecimal bumped = new BigDecimal(current)
                .multiply(BigDecimal.valueOf(feeBumpPercentage))
                .divide(BigDecimal.valueOf(100));
        return bumped.toBigInteger();
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

    // ──────────────────────────────────────────────────────────────────────
    // 17-3/17-5: ChainAdapter new interface methods — full implementations
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public Set<ChainAdapterCapability> capabilities() {
        return Set.of(
                ChainAdapterCapability.ACCOUNT_NONCE,
                ChainAdapterCapability.FINALIZED_HEAD,
                ChainAdapterCapability.REPLACE_TX
        );
    }

    /**
     * 17-5: prepareSend() — build and sign an EIP-1559 ETH transfer.
     * Uses the existing signing path to preserve all gas / nonce logic.
     * ERC-20 support deferred to Section 18.
     */
    @Override
    public PreparedTx prepareSend(SendRequest request) {
        if (!isValidAddress(request.toAddress())) {
            throw new IllegalArgumentException("Invalid EVM to-address: " + request.toAddress());
        }

        ensureConnectedChainIdMatchesConfigured();

        BigInteger nonce = getPendingNonce(request.fromAddress());
        GasPriceCache prices = fetchGasPrices();

        RawTransaction rawTx = RawTransaction.createEtherTransaction(
                configuredChainId,
                nonce,
                GAS_LIMIT,
                request.toAddress(),
                request.amountRaw(),
                prices.maxPriorityFeePerGas(),
                prices.maxFeePerGas()
        );

        // Preserve exact same signing output as legacy broadcast(BroadcastCommand)
        String signedHex = signer.sign(rawTx, configuredChainId);

        return new lab.custody.adapter.prepared.EvmPreparedTx(
                signedHex,
                nonce.longValue(),
                prices.maxFeePerGas(),
                prices.maxPriorityFeePerGas()
        );
    }

    /**
     * 17-5: broadcast(PreparedTx) — broadcast a previously-signed EVM transaction.
     */
    @Override
    @CircuitBreaker(name = "evmRpc", fallbackMethod = "broadcastPreparedFallback")
    public BroadcastResult broadcast(lab.custody.adapter.prepared.PreparedTx prepared) {
        if (!(prepared instanceof lab.custody.adapter.prepared.EvmPreparedTx evmTx)) {
            throw new IllegalArgumentException("EvmRpcAdapter requires EvmPreparedTx, got: "
                    + prepared.getClass().getSimpleName());
        }

        long start = System.nanoTime();
        boolean success = false;
        try {
            Web3j web3j = providerPool.primary();
            log.debug("event=rpc.broadcast_prepared.using_provider url={}", providerPool.primaryUrl());

            EthSendTransaction sent = web3j.ethSendRawTransaction(evmTx.signedHexTx()).send();
            if (sent.hasError()) {
                throw new BroadcastRejectedException("EVM RPC rejected transaction: " + sent.getError().getMessage());
            }

            String txHash = sent.getTransactionHash();
            if (txHash == null || txHash.isBlank()) {
                throw new IllegalStateException("RPC returned an empty tx hash");
            }

            success = true;
            return new BroadcastResult(txHash, true);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to execute EVM RPC request", e);
        } finally {
            recordRpcCall("broadcastPrepared", success, start);
        }
    }

    /**
     * 17-5: getTxStatus() — maps a TransactionReceipt to a TxStatusSnapshot.
     * PENDING if no receipt, FAILED if status=0x0, INCLUDED otherwise.
     */
    @Override
    public TxStatusSnapshot getTxStatus(String txHash) {
        Optional<TransactionReceipt> receiptOpt = getReceipt(txHash);
        if (receiptOpt.isEmpty()) {
            return new TxStatusSnapshot(TxStatusSnapshot.TxStatus.PENDING, null, null, null);
        }
        TransactionReceipt receipt = receiptOpt.get();
        Long blockNumber = receipt.getBlockNumber() != null ? receipt.getBlockNumber().longValue() : null;
        boolean failed = receipt.getStatus() != null && receipt.getStatus().equals("0x0");
        TxStatusSnapshot.TxStatus status = failed
                ? TxStatusSnapshot.TxStatus.FAILED
                : TxStatusSnapshot.TxStatus.INCLUDED;
        String revertReason = failed ? receipt.getRevertReason() : null;
        return new TxStatusSnapshot(status, blockNumber, null, revertReason);
    }

    /**
     * 17-5: getHeads() — fetches latest, safe, and finalized block numbers.
     */
    @Override
    public HeadsSnapshot getHeads() {
        long latest = getBlockNumber();
        Long safe = getNamedBlockNumber("safe");
        Long finalized = getNamedBlockNumber("finalized");
        return new HeadsSnapshot(latest, safe, finalized, System.currentTimeMillis());
    }

    /** Fetch a named block number ("safe" or "finalized"); returns null on any failure. */
    private Long getNamedBlockNumber(String blockTag) {
        try {
            return withFallback("getNamedBlock-" + blockTag, web3j -> {
                org.web3j.protocol.core.DefaultBlockParameter param =
                        org.web3j.protocol.core.DefaultBlockParameter.valueOf(blockTag);
                EthBlock response = web3j.ethGetBlockByNumber(param, false).send();
                if (response.hasError() || response.getBlock() == null) {
                    return null;
                }
                return response.getBlock().getNumber() != null
                        ? response.getBlock().getNumber().longValue()
                        : null;
            });
        } catch (Exception e) {
            log.debug("event=rpc.get_named_block.failed tag={} error={}", blockTag, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unused")
    private BroadcastResult broadcastPreparedFallback(lab.custody.adapter.prepared.PreparedTx prepared, Throwable t) {
        throw new BroadcastRejectedException(
                "EVM RPC circuit breaker is open — broadcastPrepared rejected: " + t.getMessage());
    }
}
