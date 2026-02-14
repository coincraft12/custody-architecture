package lab.custody.domain.withdrawal;

public enum WithdrawalStatus {
    W0_REQUESTED,
    W1_POLICY_CHECKED,
    W2_APPROVAL_PENDING,
    W3_APPROVED,
    W4_SIGNING,
    W5_SIGNED,
    W6_BROADCASTED,
    W7_INCLUDED,
    W8_SAFE_FINALIZED,
    W9_LEDGER_POSTED,
    W10_COMPLETED
}
