package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "mock", matchIfMissing = true)
public class EvmMockAdapter implements ChainAdapter {

    @Override
    public BroadcastResult broadcast(BroadcastCommand command) {
        return new BroadcastResult("0xEVM_MOCK_" + UUID.randomUUID().toString().substring(0, 8), true);
    }

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }
}
