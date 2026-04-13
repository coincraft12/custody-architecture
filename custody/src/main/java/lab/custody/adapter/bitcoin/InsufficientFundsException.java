package lab.custody.adapter.bitcoin;

/**
 * 19-9: Thrown when the available unlocked UTXOs cannot cover the requested send amount plus fee.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
