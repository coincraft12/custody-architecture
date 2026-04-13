package lab.custody.orchestration.policy;

import lab.custody.orchestration.CreateWithdrawalRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

@Component
@Order(10)
public class AmountLimitPolicyRule implements PolicyRule {

    private final BigInteger maxAmount;

    public AmountLimitPolicyRule(@Value("${policy.max-amount:1000}") BigInteger maxAmount) {
        this.maxAmount = maxAmount;
    }

    @Override
    public PolicyDecision evaluate(CreateWithdrawalRequest req) {
        if (req.amount().compareTo(maxAmount) > 0) {
            return PolicyDecision.reject("AMOUNT_LIMIT_EXCEEDED: max=" + maxAmount + ", requested=" + req.amount());
        }
        return PolicyDecision.allow();
    }
}
