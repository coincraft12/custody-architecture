package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;

import java.util.UUID;

public interface ChainAdapter {

    BroadcastResult broadcast(BroadcastCommand command);

    ChainType getChainType();

    record BroadcastCommand(
            UUID withdrawalId,
            String from,
            String to,
            String asset,
            long amount,
            long nonce,
            Long maxPriorityFeePerGas,
            Long maxFeePerGas
    ) {}

    record BroadcastResult(
            String txHash,
            boolean accepted
    ) {}
}
