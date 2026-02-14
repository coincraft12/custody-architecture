package lab.custody.orchestration;

import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.sim.fakechain.FakeChain;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/sim")
public class SimController {

    private final RetryReplaceService retryReplaceService;

    @PostMapping("/withdrawals/{id}/next-outcome/{outcome}")
    public ResponseEntity<Void> setNextOutcome(@PathVariable UUID id, @PathVariable String outcome) {
        FakeChain.NextOutcome o = FakeChain.NextOutcome.valueOf(outcome.toUpperCase());
        retryReplaceService.setNextOutcome(id, o);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/withdrawals/{id}/broadcast")
    public ResponseEntity<Withdrawal> broadcast(@PathVariable UUID id) {
        return ResponseEntity.ok(retryReplaceService.simulateBroadcast(id));
    }
}
