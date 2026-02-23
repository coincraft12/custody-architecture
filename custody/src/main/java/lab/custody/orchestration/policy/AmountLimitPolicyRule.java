package lab.custody.orchestration.policy;

import lab.custody.orchestration.CreateWithdrawalRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Order(10)
public class AmountLimitPolicyRule implements PolicyRule {

    private final BigDecimal maxAmountEth;

    public AmountLimitPolicyRule(@Value("${policy.max-amount:1000}") BigDecimal maxAmountEth) {
        this.maxAmountEth = maxAmountEth;
    }

    @Override
    public PolicyDecision evaluate(CreateWithdrawalRequest req) {
        if (req.amount().compareTo(maxAmountEth) > 0) {
            return PolicyDecision.reject("AMOUNT_LIMIT_EXCEEDED: max=" + maxAmountEth + ", requested=" + req.amount());
        }
        return PolicyDecision.allow();
    }
}
