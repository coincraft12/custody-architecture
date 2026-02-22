package lab.custody.orchestration;

import lab.custody.domain.policy.PolicyAuditLog;
import lab.custody.domain.withdrawal.Withdrawal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/withdrawals")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    // Main API entry for creating a withdrawal.
    // Idempotency-Key is required so duplicate client retries do not create duplicate withdrawals.
    @PostMapping
    public ResponseEntity<Withdrawal> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateWithdrawalRequest req
    ) {
        Withdrawal w = withdrawalService.createOrGet(idempotencyKey, req);
        return ResponseEntity.ok(w);
    }

    // Read the current withdrawal state to observe status transitions after retry/replace/confirmation.
    @GetMapping("/{id}")
    public ResponseEntity<Withdrawal> get(@PathVariable UUID id) {
        return ResponseEntity.ok(withdrawalService.get(id));
    }

    // Return policy audit records (allow/reject + reason) for operational traceability.
    @GetMapping("/{id}/policy-audits")
    public ResponseEntity<List<PolicyAuditLog>> getPolicyAudits(@PathVariable UUID id) {
        return ResponseEntity.ok(withdrawalService.getPolicyAudits(id));
    }
}
