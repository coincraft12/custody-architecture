package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChainAdapterRouter {

    private final List<ChainAdapter> adapters;

    public ChainAdapterRouter(List<ChainAdapter> adapters) {
        this.adapters = adapters;
    }

    public ChainAdapter resolve(ChainType type) {
        return adapters.stream()
                .filter(a -> a.getChainType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No adapter for type: " + type));
    }
}
