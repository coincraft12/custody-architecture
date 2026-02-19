package lab.custody.orchestration;

import lab.custody.sim.fakechain.FakeChain;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/sim")
public class SimController {

    private final RetryReplaceService retryReplaceService;
    private final FakeChain fakeChain;

    @PostMapping("/withdrawals/{id}/next-outcome/{outcome}")
    public void setNextOutcome(@PathVariable UUID id, @PathVariable FakeChain.NextOutcome outcome) {
        fakeChain.setNextOutcome(id, outcome);
    }

    @PostMapping("/withdrawals/{id}/broadcast")
    public Object broadcast(@PathVariable UUID id) {
        return retryReplaceService.simulateBroadcast(id);
    }

    @PostMapping("/withdrawals/{id}/confirm")
    public Object confirm(@PathVariable UUID id) {
        return retryReplaceService.simulateConfirmation(id);
    }
}
