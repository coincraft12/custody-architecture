package lab.custody.domain.txattempt;

public enum TxAttemptStatus {
    CREATED,
    BROADCASTED,
    INCLUDED,
    SUCCESS,
    FAILED,
    FAILED_TIMEOUT,
    REPLACED
}
