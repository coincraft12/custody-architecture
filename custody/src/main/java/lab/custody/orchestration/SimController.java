package lab.custody.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/sim")
public class SimController {

    private final RetryReplaceService retryReplaceService;

    @PostMapping("/withdrawals/{id}/next-outcome/{outcome}")
    public void setNextOutcome(
            @PathVariable UUID id,
            @PathVariable String outcome
    ) {
        RetryReplaceService.NextOutcome o =
                RetryReplaceService.NextOutcome.valueOf(outcome.toUpperCase());

        retryReplaceService.setNextOutcome(id, o);
    }

    @PostMapping("/withdrawals/{id}/broadcast")
    public Object broadcast(@PathVariable UUID id) {
        return retryReplaceService.simulateBroadcast(id);
    }
}
