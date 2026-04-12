package lab.custody.orchestration;

import lab.custody.domain.approval.ApprovalTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 출금 승인 워크플로 API 컨트롤러.
 *
 * <p>10-2-3: 금액 기반 동적 승인 엔드포인트.
 *
 * <ul>
 *   <li>POST /withdrawals/{id}/approve — 승인 결정 (APPROVER 역할 필요)</li>
 *   <li>POST /withdrawals/{id}/reject  — 거부 결정 (APPROVER 역할 필요)</li>
 *   <li>GET  /withdrawals/{id}/approval-task — 승인 태스크 조회</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/withdrawals")
@Slf4j
public class ApprovalController {

    private final ApprovalService approvalService;

    /**
     * 개별 승인자 승인 결정.
     * Body: { "approverId": "approver-user-id", "reason": "optional reason" }
     *
     * requiredApprovals 충족 시 출금이 W3_APPROVED로 자동 전이된다.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApprovalTask> approve(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String approverId = body.getOrDefault("approverId", "unknown");
        String reason = body.get("reason");

        // withdrawalId로 ApprovalTask 조회 후 승인
        ApprovalTask task = approvalService.getTaskByWithdrawalId(id);
        log.info("event=approval.controller.approve withdrawalId={} taskId={} approverId={}", id, task.getId(), approverId);
        ApprovalTask updated = approvalService.approve(task.getId(), approverId, reason);
        return ResponseEntity.ok(updated);
    }

    /**
     * 개별 승인자 거부 결정.
     * Body: { "approverId": "approver-user-id", "reason": "reason for rejection" }
     *
     * 거부 시 출금이 W0_POLICY_REJECTED로 전이된다.
     */
    @PostMapping("/{id}/reject-approval")
    public ResponseEntity<ApprovalTask> reject(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String approverId = body.getOrDefault("approverId", "unknown");
        String reason = body.getOrDefault("reason", "rejected by approver");

        ApprovalTask task = approvalService.getTaskByWithdrawalId(id);
        log.info("event=approval.controller.reject withdrawalId={} taskId={} approverId={}", id, task.getId(), approverId);
        ApprovalTask updated = approvalService.reject(task.getId(), approverId, reason);
        return ResponseEntity.ok(updated);
    }

    /**
     * 출금의 승인 태스크 조회.
     */
    @GetMapping("/{id}/approval-task")
    public ResponseEntity<ApprovalTask> getApprovalTask(@PathVariable UUID id) {
        log.info("event=approval.controller.get_task withdrawalId={}", id);
        ApprovalTask task = approvalService.getTaskByWithdrawalId(id);
        return ResponseEntity.ok(task);
    }
}
