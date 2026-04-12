package lab.custody.orchestration;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lab.custody.domain.ledger.LedgerEntry;
import lab.custody.domain.policy.PolicyAuditLog;
import lab.custody.domain.withdrawal.Withdrawal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/withdrawals")
@Slf4j
@Tag(name = "Withdrawals", description = "Withdrawal lifecycle — create, approve, reject, and query")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    // Main API entry for creating a withdrawal.
    // Idempotency-Key is required so duplicate client retries do not create duplicate withdrawals.
    @Operation(summary = "Create withdrawal", description = "Create a new withdrawal request. If the idempotency key already exists, returns the existing withdrawal.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Withdrawal created or idempotent hit"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "409", description = "Idempotency key conflict (different body)")
    })
    @PostMapping
    public ResponseEntity<Withdrawal> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateWithdrawalRequest req
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
    @Operation(summary = "Get withdrawal", description = "Returns the current state of a withdrawal by ID.")
    @ApiResponse(responseCode = "200", description = "Withdrawal found")
    @GetMapping("/{id}")
    public ResponseEntity<Withdrawal> get(@PathVariable UUID id) {
        log.info("event=withdrawal.get.request withdrawalId={}", id);
        Withdrawal withdrawal = withdrawalService.get(id);
        log.info("event=withdrawal.get.response withdrawalId={} status={}", withdrawal.getId(), withdrawal.getStatus());
        return ResponseEntity.ok(withdrawal);
    }

    // Return policy audit records (allow/reject + reason) for operational traceability.
    @Operation(summary = "Get policy audits", description = "Returns policy evaluation audit records for a withdrawal.")
    @GetMapping("/{id}/policy-audits")
    public ResponseEntity<List<PolicyAuditLog>> getPolicyAudits(@PathVariable UUID id) {
        log.info("event=withdrawal.policy_audits.request withdrawalId={}", id);
        List<PolicyAuditLog> audits = withdrawalService.getPolicyAudits(id);
        log.info("event=withdrawal.policy_audits.response withdrawalId={} count={}", id, audits.size());
        return ResponseEntity.ok(audits);
    }

    // Return ledger entries (RESERVE + SETTLE) to observe the financial journal records.
    @Operation(summary = "Get ledger entries", description = "Returns RESERVE and SETTLE ledger entries for a withdrawal.")
    @GetMapping("/{id}/ledger")
    public ResponseEntity<List<LedgerEntry>> getLedger(@PathVariable UUID id) {
        log.info("event=withdrawal.ledger.request withdrawalId={}", id);
        List<LedgerEntry> entries = withdrawalService.getLedgerEntries(id);
        log.info("event=withdrawal.ledger.response withdrawalId={} count={}", id, entries.size());
        return ResponseEntity.ok(entries);
    }
}
