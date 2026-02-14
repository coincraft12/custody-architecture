package lab.custody.orchestration;

import lab.custody.domain.txattempt.TxAttempt;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/withdrawals")
public class AttemptController {

    private final AttemptService attemptService;

    @GetMapping("/{id}/attempts")
    public ResponseEntity<List<TxAttempt>> listAttempts(@PathVariable UUID id) {
        return ResponseEntity.ok(attemptService.listAttempts(id));
    }
}
