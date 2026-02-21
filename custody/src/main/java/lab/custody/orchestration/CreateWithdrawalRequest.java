package lab.custody.orchestration;

import java.math.BigDecimal;

public record CreateWithdrawalRequest(
        String chainType,
        String fromAddress,
        String toAddress,
        String asset,
        BigDecimal amount
) {}