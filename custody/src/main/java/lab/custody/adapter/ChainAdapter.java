package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.Optional;
import java.util.UUID;

public interface ChainAdapter {

    BroadcastResult broadcast(BroadcastCommand command);

    ChainType getChainType();

    /**
     * 12-1-3: Query the chain for a transaction receipt.
     * Returns empty if the transaction is not yet included or the chain type does not support receipts.
     */
    default Optional<TransactionReceipt> getTransactionReceipt(String txHash) {
        return Optional.empty();
    }

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
