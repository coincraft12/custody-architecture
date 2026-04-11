package lab.custody.orchestration.whitelist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterAddressRequest(
        @NotBlank(message = "address is required")
        @Pattern(regexp = "0x[0-9a-fA-F]{40}", message = "address must be a valid EVM address (0x + 40 hex chars)")
        String address,

        @NotBlank(message = "chainType is required")
        String chainType,

        @NotBlank(message = "registeredBy is required")
        @Size(max = 255, message = "registeredBy must be 255 characters or less")
        String registeredBy,

        @Size(max = 255, message = "note must be 255 characters or less")
        String note
) {}
