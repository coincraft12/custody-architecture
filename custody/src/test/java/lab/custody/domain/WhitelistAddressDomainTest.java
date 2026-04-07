package lab.custody.domain;

import lab.custody.domain.whitelist.WhitelistAddress;
import lab.custody.domain.whitelist.WhitelistStatus;
import lab.custody.domain.withdrawal.ChainType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * WhitelistAddress 도메인 엔티티 단위 테스트.
 *
 * 검증 항목:
 *  1. register() 팩토리가 REGISTERED 상태로 생성
 *  2. approve() REGISTERED → HOLDING, approvedBy / activeAfter 기록
 *  3. approve() REGISTERED 아닌 상태에서 IllegalStateException
 *  4. activate() HOLDING → ACTIVE
 *  5. activate() HOLDING 아닌 상태에서 IllegalStateException
 *  6. revoke() 모든 상태에서 REVOKED 로 전환
 *  7. revoke() 이미 REVOKED 상태에서 IllegalStateException
 *  8. isActive() 상태별 반환값
 */
class WhitelistAddressDomainTest {

    private WhitelistAddress newRegistered() {
        return WhitelistAddress.register("0xaddr", ChainType.EVM, "admin", "test note", 48L);
    }

    @Test
    void register_factory_createsRegisteredStatus() {
        WhitelistAddress entry = newRegistered();

        assertThat(entry.getStatus()).isEqualTo(WhitelistStatus.REGISTERED);
        assertThat(entry.getAddress()).isEqualTo("0xaddr");
        assertThat(entry.getChainType()).isEqualTo(ChainType.EVM);
        assertThat(entry.getRegisteredBy()).isEqualTo("admin");
        assertThat(entry.getNote()).isEqualTo("test note");
        assertThat(entry.getHoldDurationHours()).isEqualTo(48L);
        assertThat(entry.getRegisteredAt()).isNotNull();
        assertThat(entry.getUpdatedAt()).isNotNull();
        assertThat(entry.getApprovedBy()).isNull();
        assertThat(entry.getApprovedAt()).isNull();
        assertThat(entry.getActiveAfter()).isNull();
    }

    @Test
    void approve_fromRegistered_transitionsToHolding() {
        WhitelistAddress entry = newRegistered();
        Instant before = Instant.now();

        entry.approve("approver1");

        assertThat(entry.getStatus()).isEqualTo(WhitelistStatus.HOLDING);
        assertThat(entry.getApprovedBy()).isEqualTo("approver1");
        assertThat(entry.getApprovedAt()).isNotNull();
        assertThat(entry.getActiveAfter()).isNotNull();
        // holdDurationHours=48 → activeAfter ≈ approvedAt + 48h
        assertThat(entry.getActiveAfter()).isAfter(before.plusSeconds(47 * 3600));
    }

    @Test
    void approve_withZeroHoldDuration_activeAfterEqualsApprovedAt() {
        WhitelistAddress entry = WhitelistAddress.register("0xquick", ChainType.EVM, "admin", "note", 0L);

        entry.approve("approver2");

        assertThat(entry.getStatus()).isEqualTo(WhitelistStatus.HOLDING);
        // activeAfter = approvedAt + 0s, so activeAfter <= now (scheduler can promote immediately)
        assertThat(entry.getActiveAfter()).isBeforeOrEqualTo(Instant.now().plusSeconds(1));
    }

    @Test
    void approve_whenNotRegistered_throwsIllegalStateException() {
        WhitelistAddress entry = newRegistered();
        entry.approve("admin");
        // now HOLDING

        assertThatThrownBy(() -> entry.approve("admin2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REGISTERED");
    }

    @Test
    void activate_fromHolding_transitionsToActive() {
        WhitelistAddress entry = newRegistered();
        entry.approve("admin");

        entry.activate();

        assertThat(entry.getStatus()).isEqualTo(WhitelistStatus.ACTIVE);
    }

    @Test
    void activate_whenNotHolding_throwsIllegalStateException() {
        WhitelistAddress entry = newRegistered();
        // still REGISTERED

        assertThatThrownBy(() -> entry.activate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOLDING");
    }

    @Test
    void revoke_fromRegistered_transitionsToRevoked() {
        WhitelistAddress entry = newRegistered();

        entry.revoke("revoker1");

        assertThat(entry.getStatus()).isEqualTo(WhitelistStatus.REVOKED);
        assertThat(entry.getRevokedBy()).isEqualTo("revoker1");
    }

    @Test
    void revoke_fromHolding_transitionsToRevoked() {
        WhitelistAddress entry = newRegistered();
        entry.approve("admin");

        entry.revoke("revoker2");

        assertThat(entry.getStatus()).isEqualTo(WhitelistStatus.REVOKED);
    }

    @Test
    void revoke_fromActive_transitionsToRevoked() {
        WhitelistAddress entry = newRegistered();
        entry.approve("admin");
        entry.activate();

        entry.revoke("revoker3");

        assertThat(entry.getStatus()).isEqualTo(WhitelistStatus.REVOKED);
    }

    @Test
    void revoke_whenAlreadyRevoked_throwsIllegalStateException() {
        WhitelistAddress entry = newRegistered();
        entry.revoke("first");

        assertThatThrownBy(() -> entry.revoke("second"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REVOKED");
    }

    @Test
    void isActive_returnsTrueOnlyWhenActive() {
        WhitelistAddress registered = newRegistered();
        assertThat(registered.isActive()).isFalse();

        registered.approve("admin");
        assertThat(registered.isActive()).isFalse(); // HOLDING is not active

        registered.activate();
        assertThat(registered.isActive()).isTrue(); // ACTIVE

        registered.revoke("admin");
        assertThat(registered.isActive()).isFalse(); // REVOKED is not active
    }
}
