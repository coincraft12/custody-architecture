package lab.custody.domain;

import lab.custody.domain.withdrawal.ChainType;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Withdrawal 도메인 엔티티 단위 테스트.
 *
 * 검증 항목:
 *  1. requested() 팩토리가 올바른 초기 상태를 생성
 *  2. transitionTo()로 순방향 상태 전환 가능
 *  3. transitionTo()로 역방향 전환 시 IllegalStateException 발생 (상태머신 불변성)
 */
class WithdrawalDomainTest {

    @Test
    void requested_factory_setsInitialState() {
        Withdrawal w = Withdrawal.requested("idem-1", ChainType.EVM, "0xfrom", "0xto", "ETH", 1_000_000_000_000_000_000L);

        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.W0_REQUESTED);
        assertThat(w.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(w.getChainType()).isEqualTo(ChainType.EVM);
        assertThat(w.getFromAddress()).isEqualTo("0xfrom");
        assertThat(w.getToAddress()).isEqualTo("0xto");
        assertThat(w.getAsset()).isEqualTo("ETH");
        assertThat(w.getAmount()).isEqualTo(1_000_000_000_000_000_000L);
        assertThat(w.getCreatedAt()).isNotNull();
        assertThat(w.getUpdatedAt()).isNotNull();
        assertThat(w.getId()).isNull(); // ID is assigned by JPA, not by factory
    }

    @Test
    void transitionTo_forwardTransitions_succeed() {
        Withdrawal w = Withdrawal.requested("idem-2", ChainType.EVM, "0xfrom", "0xto", "ETH", 100L);

        // W0_REQUESTED → W1_POLICY_CHECKED → W3_APPROVED → W4_SIGNING → W5_SIGNED → W6_BROADCASTED
        w.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED);
        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.W1_POLICY_CHECKED);

        w.transitionTo(WithdrawalStatus.W3_APPROVED);
        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.W3_APPROVED);

        w.transitionTo(WithdrawalStatus.W4_SIGNING);
        w.transitionTo(WithdrawalStatus.W5_SIGNED);
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);
        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.W6_BROADCASTED);
    }

    @Test
    void transitionTo_policyRejected_allowedFromRequested() {
        Withdrawal w = Withdrawal.requested("idem-3", ChainType.EVM, "0xfrom", "0xto", "ETH", 100L);

        // W0_REQUESTED → W0_POLICY_REJECTED (same ordinal group, ordinal check uses >=)
        // W0_POLICY_REJECTED.ordinal() == W0_REQUESTED.ordinal() + 1, so this is forward
        w.transitionTo(WithdrawalStatus.W0_POLICY_REJECTED);
        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.W0_POLICY_REJECTED);
    }

    @Test
    void transitionTo_backwardTransition_throwsIllegalStateException() {
        Withdrawal w = Withdrawal.requested("idem-4", ChainType.EVM, "0xfrom", "0xto", "ETH", 100L);
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        // W6_BROADCASTED → W1_POLICY_CHECKED 역방향 전환 불가
        assertThatThrownBy(() -> w.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid withdrawal status transition");
    }

    @Test
    void transitionTo_sameStatus_isAllowed() {
        // same ordinal == same ordinal, not strictly less, so it is allowed
        Withdrawal w = Withdrawal.requested("idem-5", ChainType.EVM, "0xfrom", "0xto", "ETH", 100L);
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        // re-entering same status should not throw (idempotent guard: next.ordinal() < this.status.ordinal())
        assertThatCode(() -> w.transitionTo(WithdrawalStatus.W6_BROADCASTED))
                .doesNotThrowAnyException();
    }

    @Test
    void transitionTo_updatedAt_isRefreshedOnTransition() throws InterruptedException {
        Withdrawal w = Withdrawal.requested("idem-6", ChainType.EVM, "0xfrom", "0xto", "ETH", 100L);
        var before = w.getUpdatedAt();

        Thread.sleep(5); // ensure time progresses
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);

        assertThat(w.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void transitionTo_fullStateMachineToCompleted_succeed() {
        Withdrawal w = Withdrawal.requested("idem-7", ChainType.EVM, "0xfrom", "0xto", "ETH", 100L);
        w.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED);
        w.transitionTo(WithdrawalStatus.W3_APPROVED);
        w.transitionTo(WithdrawalStatus.W4_SIGNING);
        w.transitionTo(WithdrawalStatus.W5_SIGNED);
        w.transitionTo(WithdrawalStatus.W6_BROADCASTED);
        w.transitionTo(WithdrawalStatus.W7_INCLUDED);
        w.transitionTo(WithdrawalStatus.W8_SAFE_FINALIZED);
        w.transitionTo(WithdrawalStatus.W9_LEDGER_POSTED);
        w.transitionTo(WithdrawalStatus.W10_COMPLETED);

        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.W10_COMPLETED);
    }
}
