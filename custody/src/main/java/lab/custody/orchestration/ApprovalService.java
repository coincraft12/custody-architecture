package lab.custody.orchestration;

import lab.custody.domain.approval.ApprovalDecision;
import lab.custody.domain.approval.ApprovalDecisionRepository;
import lab.custody.domain.approval.ApprovalDecisionType;
import lab.custody.domain.approval.ApprovalTask;
import lab.custody.domain.approval.ApprovalTaskRepository;
import lab.custody.domain.approval.ApprovalTaskStatus;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 출금 승인 워크플로 서비스.
 *
 * <p>출금 금액 기반으로 승인 필요 여부와 필요 승인 수(4-eyes)를 동적으로 결정한다.
 *
 * <p>정책:
 * <ul>
 *   <li>금액 &lt; {@code custody.approval.low-risk-threshold}: 승인 불필요 (auto-approve)</li>
 *   <li>{@code low-risk-threshold} ≤ 금액 &lt; {@code high-risk-threshold}: 1인 승인 필요</li>
 *   <li>금액 ≥ {@code high-risk-threshold}: 2인 승인 필요 (4-eyes)</li>
 * </ul>
 *
 * <p>구현 섹션: 10-1-4~10-1-7, 10-2-1~10-2-2
 */
@Service
@Slf4j
public class ApprovalService {

    private static final long WEI_PER_ETH = 1_000_000_000_000_000_000L;

    private final ApprovalTaskRepository taskRepository;
    private final ApprovalDecisionRepository decisionRepository;
    private final WithdrawalRepository withdrawalRepository;

    /**
     * 10-2-1: 1인 승인 임계값 (ETH).
     * 이 금액 이상이면 최소 1명의 승인자가 필요.
     * 기본값: 0.5 ETH
     */
    @Value("${custody.approval.low-risk-threshold-eth:0.5}")
    private BigDecimal lowRiskThresholdEth;

    /**
     * 10-2-1: 2인 승인 임계값 (ETH) — 4-eyes 정책.
     * 이 금액 이상이면 최소 2명의 승인자가 필요.
     * 기본값: 1.0 ETH
     */
    @Value("${custody.approval.high-risk-threshold-eth:1.0}")
    private BigDecimal highRiskThresholdEth;

    /**
     * 승인 태스크 만료 시간 (시간 단위).
     * 기본값: 24h
     */
    @Value("${custody.approval.expiry-hours:24}")
    private long approvalExpiryHours;

    /**
     * 승인 워크플로 활성화 여부.
     * false(기본값)이면 모든 출금을 자동 승인 (하위 호환성 유지).
     */
    @Value("${custody.approval.enabled:false}")
    private boolean approvalEnabled;

    public ApprovalService(
            ApprovalTaskRepository taskRepository,
            ApprovalDecisionRepository decisionRepository,
            WithdrawalRepository withdrawalRepository) {
        this.taskRepository = taskRepository;
        this.decisionRepository = decisionRepository;
        this.withdrawalRepository = withdrawalRepository;
    }

    /**
     * 10-1-7: 출금 생성 시 호출. 승인이 필요하면 태스크를 생성하고 false를 반환(W2_APPROVAL_PENDING).
     * 승인 불필요이면 즉시 true 반환(처리 계속).
     *
     * @param withdrawal 출금 엔티티 (W1_POLICY_CHECKED 상태)
     * @return true = 바로 처리, false = 승인 대기 필요
     */
    public boolean requestApproval(Withdrawal withdrawal) {
        if (!approvalEnabled) {
            // 승인 워크플로 비활성화 — 기존 동작(자동 승인) 유지
            return true;
        }

        int requiredApprovals = computeRequiredApprovals(withdrawal.getAmount());
        if (requiredApprovals == 0) {
            log.info("event=approval.auto_approved withdrawalId={} amount={}", withdrawal.getId(), withdrawal.getAmount());
            return true;
        }

        // 승인 태스크 생성
        String riskTier = computeRiskTier(withdrawal.getAmount());
        ApprovalTask task = ApprovalTask.create(
                withdrawal.getId(),
                riskTier,
                requiredApprovals,
                Instant.now().plusSeconds(approvalExpiryHours * 3600L)
        );
        taskRepository.save(task);

        // 출금 상태를 W2_APPROVAL_PENDING으로 전이
        withdrawal.transitionTo(WithdrawalStatus.W2_APPROVAL_PENDING);
        withdrawalRepository.save(withdrawal);

        log.info("event=approval.task_created withdrawalId={} taskId={} riskTier={} requiredApprovals={} amount={}",
                withdrawal.getId(), task.getId(), riskTier, requiredApprovals, withdrawal.getAmount());
        return false;
    }

    /**
     * 10-1-5: 개별 승인자 결정 반영.
     *
     * <p>동일 승인자의 중복 결정은 409 예외.
     * requiredApprovals 충족 시 태스크 APPROVED 전이 + 출금 W3_APPROVED 전이.
     *
     * @param taskId     ApprovalTask ID
     * @param approverId 승인자 식별자
     * @param reason     결정 사유 (선택)
     * @return 업데이트된 ApprovalTask
     */
    @Transactional
    public ApprovalTask approve(UUID taskId, String approverId, String reason) {
        ApprovalTask task = loadTask(taskId);

        if (decisionRepository.existsByApprovalTaskIdAndApproverId(taskId, approverId)) {
            throw new InvalidRequestException("승인자 " + approverId + "는 이미 이 태스크에 결정을 내렸습니다.");
        }

        try {
            ApprovalDecision decision = ApprovalDecision.of(
                    taskId, approverId, ApprovalDecisionType.APPROVED, reason);
            decisionRepository.save(decision);
        } catch (DataIntegrityViolationException e) {
            throw new InvalidRequestException("동시 중복 승인 감지: 승인자 " + approverId);
        }

        boolean fullyApproved = task.recordApproval();
        ApprovalTask savedTask = taskRepository.save(task);

        if (fullyApproved) {
            // 출금 W3_APPROVED 전이
            Withdrawal withdrawal = loadWithdrawal(task.getWithdrawalId());
            withdrawal.transitionTo(WithdrawalStatus.W3_APPROVED);
            withdrawalRepository.save(withdrawal);
            log.info("event=approval.task_approved taskId={} withdrawalId={} approvedCount={} requiredApprovals={}",
                    taskId, task.getWithdrawalId(), task.getApprovedCount(), task.getRequiredApprovals());
        } else {
            log.info("event=approval.decision_recorded taskId={} approverId={} approvedCount={} requiredApprovals={}",
                    taskId, approverId, savedTask.getApprovedCount(), savedTask.getRequiredApprovals());
        }

        return savedTask;
    }

    /**
     * 10-1-6: 거부 결정.
     *
     * <p>태스크 REJECTED 전이 + 출금 W0_POLICY_REJECTED 전이.
     *
     * @param taskId     ApprovalTask ID
     * @param approverId 거부자 식별자
     * @param reason     거부 사유
     * @return 업데이트된 ApprovalTask
     */
    @Transactional
    public ApprovalTask reject(UUID taskId, String approverId, String reason) {
        ApprovalTask task = loadTask(taskId);

        ApprovalDecision decision = ApprovalDecision.of(
                taskId, approverId, ApprovalDecisionType.REJECTED, reason);
        decisionRepository.save(decision);

        task.reject();
        ApprovalTask savedTask = taskRepository.save(task);

        // 출금 W0_POLICY_REJECTED 전이
        Withdrawal withdrawal = loadWithdrawal(task.getWithdrawalId());
        withdrawal.transitionTo(WithdrawalStatus.W0_POLICY_REJECTED);
        withdrawalRepository.save(withdrawal);

        log.info("event=approval.task_rejected taskId={} withdrawalId={} approverId={} reason={}",
                taskId, task.getWithdrawalId(), approverId, reason);
        return savedTask;
    }

    /**
     * 승인 태스크 단건 조회.
     */
    @Transactional(readOnly = true)
    public ApprovalTask getTask(UUID taskId) {
        return loadTask(taskId);
    }

    /**
     * 출금 ID로 승인 태스크 조회.
     */
    @Transactional(readOnly = true)
    public ApprovalTask getTaskByWithdrawalId(UUID withdrawalId) {
        return taskRepository.findByWithdrawalId(withdrawalId)
                .orElseThrow(() -> new InvalidRequestException(
                        "승인 태스크를 찾을 수 없습니다: withdrawalId=" + withdrawalId));
    }

    // ─────────────────────────── private helpers ───────────────────────────

    /**
     * 10-2-2: 금액 기반 필요 승인 수 결정.
     *
     * @param amountWei 금액 (wei)
     * @return 필요 승인 수 (0 = auto-approve)
     */
    int computeRequiredApprovals(long amountWei) {
        BigDecimal amountEth = BigDecimal.valueOf(amountWei)
                .divide(BigDecimal.valueOf(WEI_PER_ETH));

        if (amountEth.compareTo(lowRiskThresholdEth) < 0) {
            return 0; // auto-approve
        } else if (amountEth.compareTo(highRiskThresholdEth) < 0) {
            return 1; // 1인 승인
        } else {
            return 2; // 4-eyes (2인 승인)
        }
    }

    private String computeRiskTier(long amountWei) {
        int required = computeRequiredApprovals(amountWei);
        return switch (required) {
            case 0 -> "LOW";
            case 1 -> "MEDIUM";
            default -> "HIGH";
        };
    }

    private ApprovalTask loadTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new InvalidRequestException("승인 태스크를 찾을 수 없습니다: " + taskId));
    }

    private Withdrawal loadWithdrawal(UUID withdrawalId) {
        return withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalStateException("출금을 찾을 수 없습니다: " + withdrawalId));
    }
}
