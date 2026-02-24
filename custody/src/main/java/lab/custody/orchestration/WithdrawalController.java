package lab.custody.orchestration;

import lab.custody.domain.policy.PolicyAuditLog;
import lab.custody.domain.withdrawal.Withdrawal;
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
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    // Main API entry for creating a withdrawal.
    // Idempotency-Key is required so duplicate client retries do not create duplicate withdrawals.
    @PostMapping
    public ResponseEntity<Withdrawal> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateWithdrawalRequest req
    ) {
        log.info(
                "event=withdrawal.create.request chainType={} asset={} amount={} toAddress={} idempotencyKeyPresent={}",
                req.chainType(),
                req.asset(),
                req.amount(),
                req.toAddress(),
                idempotencyKey != null && !idempotencyKey.isBlank()
        );
        Withdrawal w = withdrawalService.createOrGet(idempotencyKey, req);
        log.info(
                "event=withdrawal.create.response withdrawalId={} status={}",
                w.getId(),
                w.getStatus()
        );
        return ResponseEntity.ok(w);
    }

    // Read the current withdrawal state to observe status transitions after retry/replace/confirmation.
    @GetMapping("/{id}")
    public ResponseEntity<Withdrawal> get(@PathVariable UUID id) {
        log.info("event=withdrawal.get.request withdrawalId={}", id);
        Withdrawal withdrawal = withdrawalService.get(id);
        log.info("event=withdrawal.get.response withdrawalId={} status={}", withdrawal.getId(), withdrawal.getStatus());
        return ResponseEntity.ok(withdrawal);
    }

    // Return policy audit records (allow/reject + reason) for operational traceability.
    @GetMapping("/{id}/policy-audits")
    public ResponseEntity<List<PolicyAuditLog>> getPolicyAudits(@PathVariable UUID id) {
        log.info("event=withdrawal.policy_audits.request withdrawalId={}", id);
        List<PolicyAuditLog> audits = withdrawalService.getPolicyAudits(id);
        log.info("event=withdrawal.policy_audits.response withdrawalId={} count={}", id, audits.size());
        return ResponseEntity.ok(audits);
    }
}
