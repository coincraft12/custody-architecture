package lab.custody.orchestration.policy;

import lab.custody.orchestration.CreateWithdrawalRequest;

public interface PolicyRule {
    PolicyDecision evaluate(CreateWithdrawalRequest req);
}
