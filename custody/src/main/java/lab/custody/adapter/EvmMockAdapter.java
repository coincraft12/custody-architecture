package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class EvmMockAdapter implements ChainAdapter {

    @Override
    public BroadcastResult broadcast(BroadcastCommand command) {

        // EVM 특징:
        // - nonce 사용
        // - txHash 존재
        String txHash = "0xEVM_" + UUID.randomUUID().toString().substring(0, 8);

        return new BroadcastResult(txHash, true);
    }

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }
}
