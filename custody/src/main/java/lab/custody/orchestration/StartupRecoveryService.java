package lab.custody.orchestration;

import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 서버 재시작 후 미완료 TX(W6_BROADCASTED 상태)를 ConfirmationTracker에 재등록한다.
 * DB에 W6_BROADCASTED로 남아있는 출금은 브로드캐스트는 완료됐지만 컨펌을 받지 못한 상태이므로
 * 재시작 시점에 다시 추적을 시작해야 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRecoveryService {

    private final WithdrawalRepository withdrawalRepository;
    private final TxAttemptRepository txAttemptRepository;

    @Autowired(required = false)
    private ConfirmationTracker confirmationTracker;

    @PostConstruct
    public void recoverBroadcastedWithdrawals() {
        if (confirmationTracker == null) {
            log.info("event=startup_recovery.skipped reason=no_confirmation_tracker");
            return;
        }

        List<Withdrawal> broadcasted = withdrawalRepository.findByStatus(WithdrawalStatus.W6_BROADCASTED);
        if (broadcasted.isEmpty()) {
            log.info("event=startup_recovery.done reregistered=0 reason=no_pending_withdrawals");
            return;
        }

        int reregistered = 0;
        int skipped = 0;

        for (Withdrawal withdrawal : broadcasted) {
            TxAttempt canonical = txAttemptRepository
                    .findFirstByWithdrawalIdAndCanonicalTrue(withdrawal.getId())
                    .orElse(null);

            if (canonical == null) {
                log.warn(
                        "event=startup_recovery.no_canonical withdrawalId={} status={}",
                        withdrawal.getId(), withdrawal.getStatus()
                );
                skipped++;
                continue;
            }

            if (canonical.getTxHash() == null) {
                log.warn(
                        "event=startup_recovery.no_tx_hash withdrawalId={} attemptId={}",
                        withdrawal.getId(), canonical.getId()
                );
                skipped++;
                continue;
            }

            if (canonical.getStatus() != TxAttemptStatus.BROADCASTED) {
                log.debug(
                        "event=startup_recovery.attempt_not_broadcasted withdrawalId={} attemptId={} status={}",
                        withdrawal.getId(), canonical.getId(), canonical.getStatus()
                );
                skipped++;
                continue;
            }

            boolean started = confirmationTracker.startTrackingByAttemptId(canonical.getId());
            if (started) {
                reregistered++;
                log.info(
                        "event=startup_recovery.reregistered withdrawalId={} attemptId={} txHash={}",
                        withdrawal.getId(), canonical.getId(), canonical.getTxHash()
                );
            } else {
                skipped++;
            }
        }

        log.info(
                "event=startup_recovery.done total={} reregistered={} skipped={}",
                broadcasted.size(), reregistered, skipped
        );
    }
}
