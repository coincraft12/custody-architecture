package lab.custody.orchestration;

import lab.custody.domain.txattempt.*;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lab.custody.sim.fakechain.FakeChain;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RetryReplaceService {

    private final WithdrawalRepository withdrawalRepository;
    private final TxAttemptRepository txAttemptRepository;
    private final AttemptService attemptService;
    private final NonceAllocator nonceAllocator;
    private final FakeChain fakeChain;

    /**
     * "broadcast"를 시뮬레이션하고 결과에 따라
     * - FAIL -> 새 Attempt 생성(누적)
     * - REPLACED -> canonical 전환
     */
    @Transactional
    public Withdrawal simulateBroadcast(UUID withdrawalId) {
        Withdrawal w = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal not found: " + withdrawalId));

        // 현재 canonical attempt 찾기
        List<TxAttempt> attempts = txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId);
        TxAttempt canonical = attempts.stream()
                .filter(TxAttempt::isCanonical)
                .max(Comparator.comparingInt(TxAttempt::getAttemptNo))
                .orElseThrow(() -> new IllegalStateException("no canonical attempt"));

        // Withdrawal 상태를 BROADCASTED로(실습2 체감용)
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        // Fake txHash 발급 + SENT_TO_RPC 전이
        canonical.setTxHash(fakeChain.newTxHash(canonical.getId()));
        canonical.transitionTo(TxAttemptStatus.A2_SENT_TO_RPC);

        FakeChain.NextOutcome outcome = fakeChain.consumeOutcome(withdrawalId);

        if (outcome == FakeChain.NextOutcome.FAIL_SYSTEM) {
            // FAILED(system) -> Attempt 누적
            canonical.markException(AttemptExceptionType.FAILED_SYSTEM, "simulated failure");
            // attempt 상태는 유지(혹은 별도 실패 상태를 두지 않으니 예외만 기록)
            // 새 Attempt 생성 (nonce 정책은 실습2에서 "같은 nonce 유지"로 체감시키자)
            long sameNonce = canonical.getNonce();
            TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), sameNonce);
            // 기존 canonical 해제, 새 canonical 채택
            canonical.setCanonical(false);
            newAttempt.setCanonical(true);

        } else if (outcome == FakeChain.NextOutcome.REPLACED) {
            // REPLACED -> 같은 nonce의 새 Attempt를 만들고 canonical 전환
            canonical.markException(AttemptExceptionType.REPLACED, "simulated replace");
            canonical.setCanonical(false);

            long sameNonce = canonical.getNonce();
            TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), sameNonce);
            newAttempt.setCanonical(true);

        } else {
            // SUCCESS -> 포함/확정 쪽은 다음 단계에서 확장
            canonical.transitionTo(TxAttemptStatus.A4_INCLUDED);
            w.transitionTo(WithdrawalStatus.W7_INCLUDED);
        }

        return w;
    }

    @Transactional
    public void setNextOutcome(UUID withdrawalId, FakeChain.NextOutcome outcome) {
        fakeChain.setNextOutcome(withdrawalId, outcome);
    }
}
