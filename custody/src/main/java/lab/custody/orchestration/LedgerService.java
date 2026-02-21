package lab.custody.orchestration;

import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final WithdrawalRepository withdrawalRepository;
    private final TxAttemptRepository txAttemptRepository;

    public Withdrawal saveWithdrawal(Withdrawal withdrawal) {
        return withdrawalRepository.save(withdrawal);
    }

    public TxAttempt saveAttempt(TxAttempt attempt) {
        return txAttemptRepository.save(attempt);
    }
}
