package lab.custody.adapter.prepared;

/**
 * Mock PreparedTx for BFT chains — carries only the sender address.
 */
public record BftMockPreparedTx(String fromAddress) implements PreparedTx {
}
