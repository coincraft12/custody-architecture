package lab.custody.orchestration.policy;

import lab.custody.orchestration.CreateWithdrawalRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PolicyEngine {

    private final List<PolicyRule> rules;

    public PolicyEngine(List<PolicyRule> rules) {
        this.rules = rules;
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
}
