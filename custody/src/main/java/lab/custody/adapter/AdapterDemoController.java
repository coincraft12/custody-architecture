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
        long nonce = resolveNonce(adapter, req.nonce());

        ChainAdapter.BroadcastResult result = adapter.broadcast(
                new ChainAdapter.BroadcastCommand(
                        UUID.randomUUID(),
                        req.from(),
                        req.to(),
                        req.asset(),
                        req.amount(),
                        nonce,
                        null,
                        null
                )
        );

        return ResponseEntity.ok(result);
    }

    public record DemoRequest(
            String from,
            String to,
            String asset,
            long amount,
            Long nonce
    ) {}

    private long resolveNonce(ChainAdapter adapter, Long nonce) {
        if (nonce != null) {
            return nonce;
        }
        if (adapter instanceof EvmRpcAdapter rpcAdapter) {
            return rpcAdapter.getPendingNonce(rpcAdapter.getSenderAddress()).longValue();
        }
        throw new IllegalArgumentException("nonce is required for non-EVM adapters");
    }
}
