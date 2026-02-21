package lab.custody.orchestration.policy;

import lab.custody.orchestration.CreateWithdrawalRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PolicyEngine {

    private final BigDecimal maxAmountEth;
    private final Set<String> toAddressWhitelist;

    public PolicyEngine(
            @Value("${policy.max-amount:1000}") BigDecimal maxAmountEth,
            @Value("${policy.whitelist-to-addresses:}") String whitelistToAddresses
    ) {
        this.maxAmountEth = maxAmountEth;
        this.toAddressWhitelist = Arrays.stream(whitelistToAddresses.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    public PolicyDecision evaluate(CreateWithdrawalRequest req) {
        if (req.amount().compareTo(maxAmountEth) > 0) {
            return PolicyDecision.reject("AMOUNT_LIMIT_EXCEEDED: max=" + maxAmountEth + ", requested=" + req.amount());
        }

        if (!toAddressWhitelist.isEmpty() && !toAddressWhitelist.contains(req.toAddress())) {
            return PolicyDecision.reject("TO_ADDRESS_NOT_WHITELISTED: " + req.toAddress());
        }

        return PolicyDecision.allow();
    }
}
