package lab.custody.orchestration.policy;

import lab.custody.orchestration.CreateWithdrawalRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PolicyEngine {

    private final long maxAmount;
    private final Set<String> toAddressWhitelist;

    public PolicyEngine(
            @Value("${policy.max-amount:1000}") long maxAmount,
            @Value("${policy.whitelist-to-addresses:}") String whitelistToAddresses
    ) {
        this.maxAmount = maxAmount;
        this.toAddressWhitelist = Arrays.stream(whitelistToAddresses.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    public PolicyDecision evaluate(CreateWithdrawalRequest req) {
        if (req.amount() > maxAmount) {
            return PolicyDecision.reject("AMOUNT_LIMIT_EXCEEDED: max=" + maxAmount + ", requested=" + req.amount());
        }

        if (!toAddressWhitelist.isEmpty() && !toAddressWhitelist.contains(req.toAddress())) {
            return PolicyDecision.reject("TO_ADDRESS_NOT_WHITELISTED: " + req.toAddress());
        }

        return PolicyDecision.allow();
    }
}
