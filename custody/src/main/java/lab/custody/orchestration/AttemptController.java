package lab.custody.orchestration;

import lab.custody.domain.txattempt.TxAttempt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/withdrawals")
@Slf4j
public class AttemptController {

    private final AttemptService attemptService;
    private final RetryReplaceService retryReplaceService;
    private final WithdrawalService withdrawalService;

    // List all attempts for a withdrawal so labs can inspect canonical changes and retry/replace history.
    @GetMapping("/{id}/attempts")
    public ResponseEntity<List<TxAttempt>> listAttempts(@PathVariable UUID id) {
        log.info("event=attempt.list.request withdrawalId={}", id);
        withdrawalService.get(id);
        List<TxAttempt> attempts = attemptService.listAttempts(id);
        log.info("event=attempt.list.response withdrawalId={} count={}", id, attempts.size());
        return ResponseEntity.ok(attempts);
    }

    // Trigger a retry flow (typically new nonce) and return the newly created attempt.
    @PostMapping("/{id}/retry")
    public ResponseEntity<TxAttempt> retry(@PathVariable UUID id) {
        log.info("event=attempt.retry.request withdrawalId={}", id);
        TxAttempt attempt = retryReplaceService.retry(id);
        log.info(
                "event=attempt.retry.response withdrawalId={} attemptId={} status={} canonical={}",
                id,
                attempt.getId(),
                attempt.getStatus(),
                attempt.isCanonical()
        );
        return ResponseEntity.ok(attempt);
    }

    // Trigger a replace flow (same nonce + fee bump) for a pending canonical attempt.
    @PostMapping("/{id}/replace")
    public ResponseEntity<TxAttempt> replace(@PathVariable UUID id) {
        log.info("event=attempt.replace.request withdrawalId={}", id);
        TxAttempt attempt = retryReplaceService.replace(id);
        log.info(
                "event=attempt.replace.response withdrawalId={} attemptId={} status={} canonical={}",
                id,
                attempt.getId(),
                attempt.getStatus(),
                attempt.isCanonical()
        );
        return ResponseEntity.ok(attempt);
    }

    // Manual sync endpoint: poll receipt synchronously and apply inclusion/result state updates.
    @PostMapping("/{id}/sync")
    public ResponseEntity<TxAttempt> sync(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "30000") long timeoutMs,
            @RequestParam(defaultValue = "1500") long pollMs
    ) {
        log.info("event=attempt.sync.request withdrawalId={} timeoutMs={} pollMs={}", id, timeoutMs, pollMs);
        TxAttempt attempt = retryReplaceService.sync(id, timeoutMs, pollMs);
        log.info(
                "event=attempt.sync.response withdrawalId={} attemptId={} status={} canonical={}",
                id,
                attempt.getId(),
                attempt.getStatus(),
                attempt.isCanonical()
        );
        return ResponseEntity.ok(attempt);
    }
}
