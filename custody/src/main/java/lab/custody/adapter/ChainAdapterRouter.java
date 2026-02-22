package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChainAdapterRouter {

    private final Map<ChainType, ChainAdapter> adaptersByChainType;

    // Build an immutable routing table once at startup and fail fast if two adapters claim the same chain type.
    public ChainAdapterRouter(List<ChainAdapter> adapters) {
        this.adaptersByChainType = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ChainAdapter::getChainType,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("Multiple adapters found for chain type: " + left.getChainType());
                        }
                ));
    }

    // Resolve the chain-specific adapter so orchestration code can stay chain-agnostic.
    public ChainAdapter resolve(ChainType type) {
        return java.util.Optional.ofNullable(adaptersByChainType.get(type))
                .orElseThrow(() -> new IllegalArgumentException("No adapter for type: " + type));
    }
}
