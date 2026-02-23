package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lab.custody.orchestration.ConfirmationTracker;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttempt;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequiredArgsConstructor
@RequestMapping("/adapter-demo")
public class AdapterDemoController {

    private final ChainAdapterRouter router;
    private final ConfirmationTracker confirmationTracker;
    private final TxAttemptRepository txAttemptRepository;
    private final WithdrawalRepository withdrawalRepository;

    @Value("${demo.track-attempt.timeout-ms:60000}")
    private long trackAttemptTimeoutMs;

    // Lab/demo endpoint that exercises adapter routing directly without the full withdrawal workflow.
    @PostMapping("/broadcast/{type}")
        public ResponseEntity<ChainAdapter.BroadcastResult> broadcast(
            @PathVariable String type,
            @RequestBody DemoRequest req
        ) {
        ChainType normalizedType = ChainType.valueOf(type.toUpperCase(Locale.ROOT));
        ChainAdapter adapter = router.resolve(normalizedType);
        long nonce = resolveNonce(adapter, req.nonce());

        long amountWei = ethToWei(req.amount());

        ChainAdapter.BroadcastResult result = adapter.broadcast(
            new ChainAdapter.BroadcastCommand(
                UUID.randomUUID(),
                req.from(),
                req.to(),
                req.asset(),
                amountWei,
                nonce,
                null,
                null
            )
        );

        return ResponseEntity.ok(result);
    }

    // Synchronously poll an attempt's receipt for demo purposes, then optionally kick async DB state tracking.
    @PostMapping("/track-attempt/{attemptId}")
    public ResponseEntity<ReceiptResponse> trackAttempt(@PathVariable String attemptId) {
        try {
            UUID id = UUID.fromString(attemptId);
            TxAttempt attempt = txAttemptRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("attempt not found: " + id));
            
            String txHash = attempt.getTxHash();
            if (txHash == null || txHash.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(new ReceiptResponse("error", "attempt has no txHash to track", null, null, null));
            }

            // Load withdrawal to get chainType
            var withdrawal = withdrawalRepository.findById(attempt.getWithdrawalId())
                    .orElseThrow(() -> new IllegalArgumentException("withdrawal not found"));
            
            ChainAdapter adapter = router.resolve(withdrawal.getChainType());
            if (!(adapter instanceof EvmRpcAdapter rpcAdapter)) {
                return ResponseEntity.badRequest()
                        .body(new ReceiptResponse("error", "only EVM RPC adapter supports receipt retrieval", null, null, null));
            }

            // Poll for receipt synchronously with timeout
            long startMs = System.currentTimeMillis();
            long pollIntervalMs = 1000; // poll every 1 second
            Optional<org.web3j.protocol.core.methods.response.TransactionReceipt> receiptOpt = Optional.empty();

            while (System.currentTimeMillis() - startMs < trackAttemptTimeoutMs) {
                receiptOpt = rpcAdapter.getReceipt(txHash);
                if (receiptOpt.isPresent()) {
                    break;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(pollIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ResponseEntity.badRequest()
                            .body(new ReceiptResponse("error", "polling interrupted", null, null, null));
                }
            }

            if (receiptOpt.isPresent()) {
                var receipt = receiptOpt.get();
                // trigger confirmation tracker to apply DB updates asynchronously
                try {
                    confirmationTracker.startTrackingByAttemptId(id);
                } catch (Exception e) {
                    // log via console as fallback (tracker has its own logging)
                    System.err.println("Failed to start confirmation tracker for attempt " + id + ": " + e.getMessage());
                }
                return ResponseEntity.ok(new ReceiptResponse(
                        "success",
                        "receipt found",
                        receipt.getTransactionHash(),
                        receipt.getBlockNumber() != null ? receipt.getBlockNumber().toString() : null,
                        receipt.getStatus() != null ? receipt.getStatus() : null
                ));
            } else {
                return ResponseEntity.status(408) // Request Timeout
                        .body(new ReceiptResponse("timeout", "receipt not found within " + trackAttemptTimeoutMs + "ms", txHash, null, null));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ReceiptResponse("error", e.getMessage(), null, null, null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ReceiptResponse("error", "unexpected error: " + e.getMessage(), null, null, null));
        }
    }

    // Fire-and-forget helper to start the confirmation tracker when the attempt id is already known.
    @PostMapping("/track-apply/{attemptId}")
    public ResponseEntity<String> trackAndApply(@PathVariable String attemptId) {
        try {
            UUID id = UUID.fromString(attemptId);
            confirmationTracker.startTrackingByAttemptId(id);
            return ResponseEntity.accepted().body("tracking started: " + attemptId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("invalid attempt id: " + attemptId);
        }
    }

    public record ReceiptResponse(
            String status,
            String message,
            String txHash,
            String blockNumber,
            String receiptStatus
    ) {}

    public record DemoRequest(
            String from,
            String to,
            String asset,
            java.math.BigDecimal amount,
            Long nonce
    ) {}

    // Convert human-friendly ETH decimal input to wei to align with adapter command units.
    private long ethToWei(java.math.BigDecimal eth) {
        if (eth == null) throw new IllegalArgumentException("amount is required");
        try {
            java.math.BigDecimal multiplier = new java.math.BigDecimal("1000000000000000000");
            java.math.BigInteger wei = eth.multiply(multiplier).toBigIntegerExact();
            return wei.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid amount: must be a decimal with up to 18 fractional digits representing ETH");
        }
    }

    // Use caller-supplied nonce when present; otherwise derive it from EVM pending state for convenience.
    private long resolveNonce(ChainAdapter adapter, Long nonce) {
        if (nonce != null) {
            return nonce;
        }
        if (adapter instanceof EvmRpcAdapter rpcAdapter) {
            return rpcAdapter.getPendingNonce(rpcAdapter.getSenderAddress()).longValue();
        }
        if (adapter instanceof EvmMockAdapter) {
            return 0L;
        }
        throw new IllegalArgumentException("nonce is required for non-EVM adapters");
    }
}
