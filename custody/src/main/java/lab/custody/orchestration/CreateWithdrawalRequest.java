package lab.custody.orchestration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigInteger;
import java.util.Locale;

// 13-1-4: @Schema for Swagger UI documentation
@Schema(description = "Request body for creating a withdrawal")
public record CreateWithdrawalRequest(
        @Schema(description = "Chain type identifier", example = "evm", allowableValues = {"evm", "bft"})
        @NotBlank(message = "chainType is required")
        String chainType,

        @Schema(description = "Source wallet address (must be whitelisted)", example = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")
        @NotBlank(message = "fromAddress is required")
        @Pattern(regexp = "0x[0-9a-fA-F]{40}", message = "fromAddress must be a valid EVM address (0x + 40 hex chars)")
        String fromAddress,

        @Schema(description = "Destination wallet address (must be whitelisted)", example = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8")
        @NotBlank(message = "toAddress is required")
        @Pattern(regexp = "0x[0-9a-fA-F]{40}", message = "toAddress must be a valid EVM address (0x + 40 hex chars)")
        String toAddress,

        @Schema(description = "Asset symbol", example = "ETH")
        @NotBlank(message = "asset is required")
        @Size(max = 20, message = "asset must be 20 characters or less")
        String asset,

        @Schema(description = "Amount in smallest indivisible unit of the asset (wei for ETH, 6-decimal units for USDC, etc.)", example = "1000000")
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than 0")
        BigInteger amount
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
