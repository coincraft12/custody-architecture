package lab.custody.orchestration.whitelist;

public record RegisterAddressRequest(
        String address,
        String chainType,
        String registeredBy,
        String note
) {}
