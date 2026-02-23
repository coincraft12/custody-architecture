package lab.custody.orchestration.policy;

import lab.custody.orchestration.CreateWithdrawalRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(20)
public class ToAddressWhitelistPolicyRule implements PolicyRule {

    private final Set<String> toAddressWhitelist;

    public ToAddressWhitelistPolicyRule(@Value("${policy.whitelist-to-addresses:}") String whitelistToAddresses) {
        this.toAddressWhitelist = Arrays.stream(whitelistToAddresses.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    @Override
    public PolicyDecision evaluate(CreateWithdrawalRequest req) {
        if (!toAddressWhitelist.isEmpty() && !toAddressWhitelist.contains(req.toAddress())) {
            return PolicyDecision.reject("TO_ADDRESS_NOT_WHITELISTED: " + req.toAddress());
        }
        return PolicyDecision.allow();
    }
}
