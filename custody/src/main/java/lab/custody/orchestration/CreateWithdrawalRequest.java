package lab.custody.orchestration;

public record CreateWithdrawalRequest(
        String fromAddress,
        String toAddress,
        String asset,
        long amount
) {}
