package lab.custody.orchestration;

public record CreateWithdrawalRequest(
        String chainType,
        String fromAddress,
        String toAddress,
        String asset,
        long amount
) {}