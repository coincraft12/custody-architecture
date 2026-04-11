package lab.custody.orchestration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Locale;

public record CreateWithdrawalRequest(
        @NotBlank(message = "chainType is required")
        String chainType,

        @NotBlank(message = "fromAddress is required")
        @Pattern(regexp = "0x[0-9a-fA-F]{40}", message = "fromAddress must be a valid EVM address (0x + 40 hex chars)")
        String fromAddress,

        @NotBlank(message = "toAddress is required")
        @Pattern(regexp = "0x[0-9a-fA-F]{40}", message = "toAddress must be a valid EVM address (0x + 40 hex chars)")
        String toAddress,

        @NotBlank(message = "asset is required")
        @Size(max = 20, message = "asset must be 20 characters or less")
        String asset,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than 0")
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
