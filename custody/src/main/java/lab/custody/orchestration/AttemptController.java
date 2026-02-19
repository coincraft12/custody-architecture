package lab.custody.orchestration;

import lab.custody.domain.txattempt.TxAttempt;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/withdrawals")
public class AttemptController {

    private final AttemptService attemptService;
    private final RetryReplaceService retryReplaceService;
    private final WithdrawalService withdrawalService;

    @GetMapping("/{id}/attempts")
    public ResponseEntity<List<TxAttempt>> listAttempts(@PathVariable UUID id) {
        withdrawalService.get(id);
        List<TxAttempt> attempts = attemptService.listAttempts(id);
        return ResponseEntity.ok(attempts);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<TxAttempt> retry(@PathVariable UUID id) {
        return ResponseEntity.ok(retryReplaceService.retry(id));
    }

    @PostMapping("/{id}/replace")
    public ResponseEntity<TxAttempt> replace(@PathVariable UUID id) {
        return ResponseEntity.ok(retryReplaceService.replace(id));
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<TxAttempt> sync(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "30000") long timeoutMs,
            @RequestParam(defaultValue = "1500") long pollMs
    ) {
        return ResponseEntity.ok(retryReplaceService.sync(id, timeoutMs, pollMs));
    }
}
