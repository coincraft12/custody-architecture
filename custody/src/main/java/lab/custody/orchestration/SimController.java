package lab.custody.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/sim")
public class SimController {

    private final RetryReplaceService retryReplaceService;

    @PostMapping("/withdrawals/{id}/broadcast")
    public Object broadcast(@PathVariable UUID id) {
        return retryReplaceService.sync(id);
    }
}
