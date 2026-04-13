package lab.custody.adapter.tron;

/**
 * 20-7: Thrown when a {@link lab.custody.adapter.prepared.TronPreparedTx} has passed its
 * expiration timestamp and can no longer be broadcast.
 *
 * <p>Callers should call {@link lab.custody.adapter.tron.TronAdapter#prepareSend} again
 * to obtain a fresh transaction.
 */
public class TransactionExpiredException extends RuntimeException {

    public TransactionExpiredException(String message) {
        super(message);
    }
}
