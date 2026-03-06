package lab.custody.domain.ledger;

public enum LedgerEntryType {
    /** 출금 승인 시 자금을 예약(RESERVE)하는 원장 기록 */
    RESERVE,
    /** 온체인 최종 확정(SAFE_FINALIZED) 후 실제 정산(SETTLE)하는 원장 기록 */
    SETTLE
}
