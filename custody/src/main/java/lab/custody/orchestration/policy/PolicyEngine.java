package lab.custody.orchestration.policy;

import jakarta.annotation.PostConstruct;
import lab.custody.domain.policy.PolicyChangeRequest;
import lab.custody.domain.policy.PolicyChangeRequestRepository;
import lab.custody.orchestration.CreateWithdrawalRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 정책 규칙 평가 엔진.
 *
 * <p>등록된 모든 PolicyRule을 순서대로 평가(fail-fast)하며,
 * 애플리케이션 시작 시 현재 규칙 목록을 policy_change_requests 테이블에 스냅샷으로 기록한다. (8-3-5)
 */
@Component
@Slf4j
public class PolicyEngine {

    private final List<PolicyRule> rules;
    private final PolicyChangeRequestRepository changeRequestRepository;

    public PolicyEngine(List<PolicyRule> rules, PolicyChangeRequestRepository changeRequestRepository) {
        this.rules = rules;
        this.changeRequestRepository = changeRequestRepository;
    }

    // Evaluate business/policy constraints before any signing or broadcast attempt is created.
    // Return both decision and reason so the caller can persist an audit trail.
    public PolicyDecision evaluate(CreateWithdrawalRequest req) {
        for (PolicyRule rule : rules) {
            PolicyDecision decision = rule.evaluate(req);
            if (!decision.allowed()) {
                return decision;
            }
        }

        return PolicyDecision.allow();
    }

    /**
     * 8-3-5: 애플리케이션 시작 시 현재 활성화된 규칙 목록을 policy_change_requests에 기록.
     * 이를 통해 서버 재시작마다 어떤 규칙이 로드되었는지 감사 이력을 추적할 수 있다.
     */
    @PostConstruct
    public void recordStartupRuleSnapshot() {
        try {
            String ruleNames = rules.stream()
                    .map(r -> r.getClass().getSimpleName())
                    .collect(Collectors.joining(","));
            String payload = String.format(
                    "{\"event\":\"startup_snapshot\",\"rules\":[%s],\"ruleCount\":%d}",
                    rules.stream()
                            .map(r -> "\"" + r.getClass().getSimpleName() + "\"")
                            .collect(Collectors.joining(",")),
                    rules.size()
            );
            changeRequestRepository.save(
                    PolicyChangeRequest.applied("POLICY_RULE_SNAPSHOT", payload, "system:startup"));
            log.info("event=policy_engine.startup_snapshot rules={} count={}", ruleNames, rules.size());
        } catch (Exception e) {
            // 감사 로그 실패가 시스템 기동을 막아서는 안 된다
            log.warn("event=policy_engine.startup_snapshot.failed error={}", e.getMessage());
        }
    }
}

