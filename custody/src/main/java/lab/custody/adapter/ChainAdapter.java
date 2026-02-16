package lab.custody.adapter;

import java.util.UUID;

public interface ChainAdapter {

    BroadcastResult broadcast(BroadcastCommand command);

    ChainType getChainType();

    enum ChainType {
        EVM,
        BFT
    }

    record BroadcastCommand(
            UUID withdrawalId,
            String from,
            String to,
            String asset,
            long amount,
            long nonce
    ) {}

    record BroadcastResult(
            String txHash,
            boolean accepted
    ) {}
}
