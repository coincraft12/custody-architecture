package lab.custody.domain.txattempt;

public enum TxAttemptStatus {
    A0_CREATED,          // nonce reserved + fee plan
    A1_SIGNED,
    A2_SENT_TO_RPC,
    A3_SEEN_IN_MEMPOOL,
    A4_INCLUDED,
    A5_CONFIRMED,
    A6_FINALIZED
}
