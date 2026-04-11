package lab.custody.adapter;

import java.util.Locale;

public class BroadcastRejectedException extends RuntimeException {

    private final boolean nonceTooLow;

    public BroadcastRejectedException(String message) {
        super(message);
        this.nonceTooLow = message != null
                && message.toLowerCase(Locale.ROOT).contains("nonce too low");
    }

    /**
     * Returns true when the RPC rejected the transaction with a "nonce too low" error,
     * indicating the on-chain nonce has advanced past the value we reserved.
     * Callers should release the reservation and re-reserve a fresh nonce.
     */
    public boolean isNonceTooLow() {
        return nonceTooLow;
    }
}
