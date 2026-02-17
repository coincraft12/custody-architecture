package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.Locale;

@RestController
@RequiredArgsConstructor
@RequestMapping("/adapter-demo")
public class AdapterDemoController {

    private final ChainAdapterRouter router;

    @PostMapping("/broadcast/{type}")
    public ResponseEntity<ChainAdapter.BroadcastResult> broadcast(
            @PathVariable String type,
            @RequestBody DemoRequest req
    ) {
        ChainType normalizedType = ChainType.valueOf(type.toUpperCase(Locale.ROOT));
        ChainAdapter adapter = router.resolve(normalizedType);

        ChainAdapter.BroadcastResult result = adapter.broadcast(
                new ChainAdapter.BroadcastCommand(
                        UUID.randomUUID(),
                        req.from(),
                        req.to(),
                        req.asset(),
                        req.amount(),
                        req.nonce()
                )
        );

        return ResponseEntity.ok(result);
    }

    public record DemoRequest(
            String from,
            String to,
            String asset,
            long amount,
            long nonce
    ) {}
}
