package lab.custody.orchestration;

import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final WithdrawalRepository withdrawalRepository;
    private final NonceAllocator nonceAllocator;
    private final AttemptService attemptService;

    @Transactional
    public Withdrawal createOrGet(String idempotencyKey, CreateWithdrawalRequest req) {
        return withdrawalRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> {
                    // 1) Withdrawal 생성
                    Withdrawal w = Withdrawal.requested(
                            idempotencyKey,
                            req.fromAddress(),
                            req.toAddress(),
                            req.asset(),
                            req.amount()
                    );

                    // 실습 1에서는 "정책/승인"을 생략하고 SIGNING까지 당겨서 체감시키자
                    w.transitionTo(WithdrawalStatus.W4_SIGNING);

                    Withdrawal saved = withdrawalRepository.save(w);

                    // 2) 첫 Attempt 생성 (nonce 예약)
                    long nonce = nonceAllocator.reserve(req.fromAddress());
                    attemptService.createAttempt(saved.getId(), req.fromAddress(), nonce);

                    return saved;
                });
    }

    @Transactional(readOnly = true)
    public Withdrawal get(UUID id) {
        return withdrawalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal not found: " + id));
    }
}
