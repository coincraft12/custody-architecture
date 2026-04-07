package lab.custody.domain;

import lab.custody.domain.txattempt.AttemptExceptionType;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * TxAttempt 도메인 엔티티 단위 테스트.
 *
 * 검증 항목:
 *  1. created() 팩토리가 올바른 초기 상태를 설정
 *  2. groupKey 형식이 "fromAddress:nonce"
 *  3. 상태 전환, 예외 마킹, 수수료 파라미터 설정
 *  4. canonical 플래그 변경
 */
class TxAttemptDomainTest {

    private static final UUID WITHDRAWAL_ID = UUID.randomUUID();

    @Test
    void created_factory_setsCorrectFields() {
        TxAttempt attempt = TxAttempt.created(WITHDRAWAL_ID, 1, "0xfrom", 42L, true);

        assertThat(attempt.getWithdrawalId()).isEqualTo(WITHDRAWAL_ID);
        assertThat(attempt.getAttemptNo()).isEqualTo(1);
        assertThat(attempt.getFromAddress()).isEqualTo("0xfrom");
        assertThat(attempt.getNonce()).isEqualTo(42L);
        assertThat(attempt.isCanonical()).isTrue();
        assertThat(attempt.getStatus()).isEqualTo(TxAttemptStatus.CREATED);
        assertThat(attempt.getCreatedAt()).isNotNull();
        assertThat(attempt.getId()).isNull(); // JPA assigns ID
    }

    @Test
    void created_nonCanonical_flagIsRespected() {
        TxAttempt attempt = TxAttempt.created(WITHDRAWAL_ID, 2, "0xfrom", 5L, false);

        assertThat(attempt.isCanonical()).isFalse();
    }

    @Test
    void groupKey_format_isAddressColonNonce() {
        String key = TxAttempt.groupKey("0xfrom", 7L);

        assertThat(key).isEqualTo("0xfrom:7");
    }

    @Test
    void groupKey_storedOnCreation_matchesStaticMethod() {
        TxAttempt attempt = TxAttempt.created(WITHDRAWAL_ID, 1, "0xaddr", 3L, true);

        assertThat(attempt.getAttemptGroupKey()).isEqualTo(TxAttempt.groupKey("0xaddr", 3L));
    }

    @Test
    void transitionTo_updatesStatus() {
        TxAttempt attempt = TxAttempt.created(WITHDRAWAL_ID, 1, "0xfrom", 0L, true);

        attempt.transitionTo(TxAttemptStatus.BROADCASTED);
        assertThat(attempt.getStatus()).isEqualTo(TxAttemptStatus.BROADCASTED);

        attempt.transitionTo(TxAttemptStatus.INCLUDED);
        assertThat(attempt.getStatus()).isEqualTo(TxAttemptStatus.INCLUDED);
    }

    @Test
    void setTxHash_updatesHash() {
        TxAttempt attempt = TxAttempt.created(WITHDRAWAL_ID, 1, "0xfrom", 0L, true);

        assertThat(attempt.getTxHash()).isNull();
        attempt.setTxHash("0xdeadbeef");
        assertThat(attempt.getTxHash()).isEqualTo("0xdeadbeef");
    }

    @Test
    void setFeeParams_updatesGasFields() {
        TxAttempt attempt = TxAttempt.created(WITHDRAWAL_ID, 1, "0xfrom", 0L, true);

        attempt.setFeeParams(1_500_000_000L, 15_000_000_000L);

        assertThat(attempt.getMaxPriorityFeePerGas()).isEqualTo(1_500_000_000L);
        assertThat(attempt.getMaxFeePerGas()).isEqualTo(15_000_000_000L);
    }

    @Test
    void markException_setsExceptionTypeAndDetail() {
        TxAttempt attempt = TxAttempt.created(WITHDRAWAL_ID, 1, "0xfrom", 0L, true);

        attempt.markException(AttemptExceptionType.REPLACED, "fee bump replacement");

        assertThat(attempt.getExceptionType()).isEqualTo(AttemptExceptionType.REPLACED);
        assertThat(attempt.getExceptionDetail()).isEqualTo("fee bump replacement");
    }

    @Test
    void setCanonical_updatesFlag() {
        TxAttempt attempt = TxAttempt.created(WITHDRAWAL_ID, 1, "0xfrom", 0L, true);
        assertThat(attempt.isCanonical()).isTrue();

        attempt.setCanonical(false);
        assertThat(attempt.isCanonical()).isFalse();
    }
}
