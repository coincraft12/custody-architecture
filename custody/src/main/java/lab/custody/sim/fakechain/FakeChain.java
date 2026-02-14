package lab.custody.sim.fakechain;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FakeChain {

    public enum NextOutcome {
        SUCCESS,
        FAIL_SYSTEM,
        REPLACED
    }

    // withdrawalId별로 다음 broadcast 결과를 주입
    private final Map<UUID, NextOutcome> nextOutcomeByWithdrawal = new ConcurrentHashMap<>();

    public void setNextOutcome(UUID withdrawalId, NextOutcome outcome) {
        nextOutcomeByWithdrawal.put(withdrawalId, outcome);
    }

    public NextOutcome consumeOutcome(UUID withdrawalId) {
    NextOutcome outcome = nextOutcomeByWithdrawal.getOrDefault(withdrawalId, NextOutcome.SUCCESS);
    nextOutcomeByWithdrawal.remove(withdrawalId);
    return outcome;
}

    // txHash 발급(가짜)
    public String newTxHash(UUID attemptId) {
        return "0xFAKE_" + attemptId.toString().replace("-", "").substring(0, 16);
    }
}
