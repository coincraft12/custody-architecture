package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.domain.txattempt.AttemptExceptionType;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class RetryReplaceService {

    public enum NextOutcome {
        SUCCESS,
        FAIL_SYSTEM,
        REPLACED
    }

    private final WithdrawalRepository withdrawalRepository;
    private final TxAttemptRepository txAttemptRepository;
    private final AttemptService attemptService;
    private final NonceAllocator nonceAllocator; // 지금은 직접 쓰지 않아도 되지만, 실습 확장 대비로 남겨둠
    private final ChainAdapterRouter router;

    // withdrawalId별로 다음 결과를 주입하는 저장소 (FakeChain 대체)
    private final Map<UUID, NextOutcome> nextOutcomeByWithdrawal = new ConcurrentHashMap<>();

    @Transactional
    public void setNextOutcome(UUID withdrawalId, NextOutcome outcome) {
        nextOutcomeByWithdrawal.put(withdrawalId, outcome);
    }

    private NextOutcome consumeOutcome(UUID withdrawalId) {
        NextOutcome outcome = nextOutcomeByWithdrawal.getOrDefault(withdrawalId, NextOutcome.SUCCESS);
        nextOutcomeByWithdrawal.remove(withdrawalId);
        return outcome;
    }

    /**
     * broadcast를 시뮬레이션하고 결과에 따라
     * - FAIL_SYSTEM -> Attempt 누적 + canonical 전환
     * - REPLACED    -> Attempt 누적 + canonical 전환
     * - SUCCESS     -> INCLUDED 전이(간단 버전)
     *
     * 핵심: 실제 txHash 발급은 Adapter가 담당한다.
     */
    @Transactional
    public Withdrawal simulateBroadcast(UUID withdrawalId) {
        Withdrawal w = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal not found: " + withdrawalId));

        // 1) canonical attempt 찾기
        List<TxAttempt> attempts = txAttemptRepository.findByWithdrawalIdOrderByAttemptNoAsc(withdrawalId);
        TxAttempt canonical = attempts.stream()
                .filter(TxAttempt::isCanonical)
                .max(Comparator.comparingInt(TxAttempt::getAttemptNo))
                .orElseThrow(() -> new IllegalStateException("no canonical attempt"));

        // 2) Withdrawal은 끊기지 않고 진행(실습2 체감용)
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        // 3) Adapter 선택 (Withdrawal에 chainType이 있다고 가정)
        // - Withdrawal.getChainType()이 "EVM"/"BFT" 형태 enum 또는 string이어야 한다.
        ChainAdapter.ChainType chainType = ChainAdapter.ChainType.valueOf(w.getChainType().name());
        ChainAdapter adapter = router.resolve(chainType);

        // 4) 동일한 명령으로 broadcast 호출
        ChainAdapter.BroadcastResult result = adapter.broadcast(
                new ChainAdapter.BroadcastCommand(
                        w.getId(),
                        w.getFromAddress(),
                        w.getToAddress(),
                        w.getAsset(),
                        w.getAmount(),
                        canonical.getNonce()
                )
        );

        // 5) Attempt에 txHash 기록 + 상태 전이
        canonical.setTxHash(result.txHash());
        canonical.transitionTo(TxAttemptStatus.A2_SENT_TO_RPC);

        // 6) Outcome 처리
        NextOutcome outcome = consumeOutcome(withdrawalId);

        if (outcome == NextOutcome.FAIL_SYSTEM) {
            // FAILED(system): 예외 기록 + 새 Attempt 생성 + canonical 전환
            canonical.markException(AttemptExceptionType.FAILED_SYSTEM, "simulated failure");
            canonical.setCanonical(false);

            long sameNonce = canonical.getNonce(); // 실습2: 같은 nonce 유지
            TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), sameNonce);
            newAttempt.setCanonical(true);

            // 저장 강제(명확히)
            txAttemptRepository.save(canonical);
            txAttemptRepository.save(newAttempt);

        } else if (outcome == NextOutcome.REPLACED) {
            // REPLACED: 예외 기록 + 새 Attempt 생성 + canonical 전환
            canonical.markException(AttemptExceptionType.REPLACED, "simulated replace");
            canonical.setCanonical(false);

            long sameNonce = canonical.getNonce(); // 실습2: 같은 nonce 유지
            TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), sameNonce);
            newAttempt.setCanonical(true);

            txAttemptRepository.save(canonical);
            txAttemptRepository.save(newAttempt);

        } else {
            // SUCCESS: included로 간단 전이(확정/최종성은 다음 실습에서 확장)
            canonical.transitionTo(TxAttemptStatus.A4_INCLUDED);
            w.transitionTo(WithdrawalStatus.W7_INCLUDED);

            txAttemptRepository.save(canonical);
        }

        // Withdrawal 저장(상태 전이 반영)
        return withdrawalRepository.save(w);
    }
}
