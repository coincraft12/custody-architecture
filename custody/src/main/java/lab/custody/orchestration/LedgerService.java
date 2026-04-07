package lab.custody.orchestration;

import lab.custody.domain.ledger.LedgerEntry;
import lab.custody.domain.ledger.LedgerEntryRepository;
import lab.custody.domain.ledger.LedgerEntryType;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final WithdrawalRepository withdrawalRepository;
    private final TxAttemptRepository txAttemptRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public Withdrawal saveWithdrawal(Withdrawal withdrawal) {
        return withdrawalRepository.save(withdrawal);
    }

    public TxAttempt saveAttempt(TxAttempt attempt) {
        return txAttemptRepository.save(attempt);
    }

    /**
     * W3_APPROVED 시점: 출금 자금 예약(RESERVE) 원장 기록.
     * createAndBroadcast() 트랜잭션 내에서 호출되므로 W6_BROADCASTED와 함께 커밋됨.
     */
    public void reserve(Withdrawal w) {
        LedgerEntry entry = LedgerEntry.reserve(
                w.getId(), w.getAsset(), w.getAmount(), w.getFromAddress(), w.getToAddress());
        ledgerEntryRepository.save(entry);
        log.info("event=ledger.reserve withdrawalId={} asset={} amount={}",
                w.getId(), w.getAsset(), w.getAmount());
    }

    /**
     * W8_SAFE_FINALIZED 시점: 온체인 최종 확정 후 정산(SETTLE) 원장 기록.
     * W9_LEDGER_POSTED → W10_COMPLETED 전환 포함.
     */
    @Transactional
    public Withdrawal settle(Withdrawal w) {
        if (!ledgerEntryRepository.existsByWithdrawalIdAndType(w.getId(), LedgerEntryType.RESERVE)) {
            throw new InvalidRequestException("cannot settle before reserve is recorded");
        }
        if (ledgerEntryRepository.existsByWithdrawalIdAndType(w.getId(), LedgerEntryType.SETTLE)) {
            throw new InvalidRequestException("settle already recorded for withdrawal: " + w.getId());
        }

        LedgerEntry entry = LedgerEntry.settle(
                w.getId(), w.getAsset(), w.getAmount(), w.getFromAddress(), w.getToAddress());
        ledgerEntryRepository.save(entry);
        log.info("event=ledger.settle withdrawalId={} asset={} amount={}",
                w.getId(), w.getAsset(), w.getAmount());

        w.transitionTo(WithdrawalStatus.W9_LEDGER_POSTED);
        w.transitionTo(WithdrawalStatus.W10_COMPLETED);
        Withdrawal saved = withdrawalRepository.save(w);
        log.info("event=ledger.settle.done withdrawalId={} status={}", saved.getId(), saved.getStatus());
        return saved;
    }

    /**
     * 출금 ID로 원장 엔트리 조회 (RESERVE → SETTLE 순).
     */
    public List<LedgerEntry> getLedgerEntries(UUID withdrawalId) {
        return ledgerEntryRepository.findByWithdrawalIdOrderByCreatedAtAsc(withdrawalId);
    }
}
