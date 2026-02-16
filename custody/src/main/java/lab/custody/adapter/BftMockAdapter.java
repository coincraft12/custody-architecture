package lab.custody.adapter;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BftMockAdapter implements ChainAdapter {

    @Override
    public BroadcastResult broadcast(BroadcastCommand command) {

        // BFT 특징:
        // - nonce 개념 약함
        // - txHash 대신 messageId 느낌
        String messageId = "BFT_" + UUID.randomUUID().toString().substring(0, 8);

        return new BroadcastResult(messageId, true);
    }

    @Override
    public ChainType getChainType() {
        return ChainType.BFT;
    }
}
