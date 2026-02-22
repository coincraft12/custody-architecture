package lab.custody.orchestration;

import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttemptService {

    private final TxAttemptRepository txAttemptRepository;

    // Create a new on-chain execution attempt for a Withdrawal.
    // attemptNo is derived from history size so retry/replace timelines stay easy to read in order.
    @Transactional
    public TxAttempt createAttempt(UUID withdrawalId, String fromAddress, long nonce) {
        List<TxAttempt> existing = txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId);
        int attemptNo = existing.size() + 1;

        // 새 attempt 생성 시 기본은 canonical=true로 만들어도 되지만,
        // 우리는 호출부에서 명확히 canonical을 설정한다.
        TxAttempt attempt = TxAttempt.created(withdrawalId, attemptNo, fromAddress, nonce, true);
        return txAttemptRepository.save(attempt);
    }


    // Return the full attempt history in attempt order for debugging, audit, and lab demos.
    @Transactional(readOnly = true)
    public List<TxAttempt> listAttempts(UUID withdrawalId) {
        return txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId);
    }
}
