package lab.custody.orchestration;

import lab.custody.domain.txattempt.TxAttempt;

import java.util.List;
import java.util.UUID;

public record AttemptListResponse(
        UUID withdrawalId,
        int attemptCount,
        List<TxAttempt> attempts
) {
}
