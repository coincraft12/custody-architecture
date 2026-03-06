package lab.custody.orchestration;

import java.math.BigDecimal;
import java.util.Locale;

public record CreateWithdrawalRequest(
        String chainType,
        String fromAddress,
        String toAddress,
        String asset,
        BigDecimal amount
) {
    public CreateWithdrawalRequest {
        fromAddress = normalizeRequiredAddress(fromAddress, "fromAddress");
        toAddress = normalizeRequiredAddress(toAddress, "toAddress");
    }

    private static String normalizeRequiredAddress(String address, String fieldName) {
        if (address == null) {
            throw new InvalidRequestException(fieldName + " is required");
        }
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new InvalidRequestException(fieldName + " is required");
        }
        return normalized;
    }
}
