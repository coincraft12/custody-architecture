package lab.custody.adapter.prepared;

/**
 * Mock PreparedTx for EVM mock adapter — carries from/to addresses.
 */
public record EvmMockPreparedTx(String fromAddress, String toAddress) implements PreparedTx {
}
