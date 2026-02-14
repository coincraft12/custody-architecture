package lab.custody.domain.txattempt;

public enum AttemptExceptionType {
    FAILED_SYSTEM,
    EXPIRED,
    DROPPED,
    REPLACED,
    REVERTED,
    RPC_INCONSISTENT
}
