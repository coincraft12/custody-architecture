package lab.custody.orchestration.policy;

import lab.custody.domain.whitelist.WhitelistAddressRepository;
import lab.custody.domain.whitelist.WhitelistStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.orchestration.CreateWithdrawalRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 9-1-2: ToAddressWhitelistPolicyRule — ACTIVE/HOLDING/REGISTERED/REVOKED/비존재 주소별 케이스.
 */
@ExtendWith(MockitoExtension.class)
class ToAddressWhitelistPolicyRuleTest {

    @Mock
    private WhitelistAddressRepository whitelistRepository;

    @InjectMocks
    private ToAddressWhitelistPolicyRule rule;

    private static final String TO_ADDRESS = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Test
    void evaluate_activeAddress_returnsAllow() {
        // ACTIVE 상태 주소 → ALLOW
        when(whitelistRepository.existsByAddressIgnoreCaseAndChainTypeAndStatus(
                TO_ADDRESS, ChainType.EVM, WhitelistStatus.ACTIVE))
                .thenReturn(true);

        PolicyDecision decision = rule.evaluate(req(TO_ADDRESS));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_holdingAddress_returnsReject() {
        // HOLDING 상태 주소 (아직 activeAfter 미경과) → REJECT
        when(whitelistRepository.existsByAddressIgnoreCaseAndChainTypeAndStatus(
                TO_ADDRESS, ChainType.EVM, WhitelistStatus.ACTIVE))
                .thenReturn(false);

        PolicyDecision decision = rule.evaluate(req(TO_ADDRESS));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("TO_ADDRESS_NOT_WHITELISTED");
        assertThat(decision.reason()).contains(TO_ADDRESS);
    }

    @Test
    void evaluate_registeredAddress_returnsReject() {
        // REGISTERED 상태 주소 (아직 approve 안 됨) → REJECT
        when(whitelistRepository.existsByAddressIgnoreCaseAndChainTypeAndStatus(
                TO_ADDRESS, ChainType.EVM, WhitelistStatus.ACTIVE))
                .thenReturn(false);

        PolicyDecision decision = rule.evaluate(req(TO_ADDRESS));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("TO_ADDRESS_NOT_WHITELISTED");
    }

    @Test
    void evaluate_revokedAddress_returnsReject() {
        // REVOKED 상태 주소 → REJECT
        when(whitelistRepository.existsByAddressIgnoreCaseAndChainTypeAndStatus(
                TO_ADDRESS, ChainType.EVM, WhitelistStatus.ACTIVE))
                .thenReturn(false);

        PolicyDecision decision = rule.evaluate(req(TO_ADDRESS));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("TO_ADDRESS_NOT_WHITELISTED");
        assertThat(decision.reason()).contains(TO_ADDRESS);
    }

    @Test
    void evaluate_nonExistentAddress_returnsReject() {
        // DB에 전혀 없는 주소 → REJECT
        when(whitelistRepository.existsByAddressIgnoreCaseAndChainTypeAndStatus(
                anyString(), any(ChainType.class), eq(WhitelistStatus.ACTIVE)))
                .thenReturn(false);

        PolicyDecision decision = rule.evaluate(req("0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("TO_ADDRESS_NOT_WHITELISTED");
        assertThat(decision.reason()).contains("0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
    }

    @Test
    void evaluate_mixedCaseAddress_normalizesBeforeQuery() {
        // 대소문자 혼합 주소를 소문자로 정규화 후 조회
        String mixedCaseAddress = "  0X70997970C51812DC3A010C7D01B50E0D17DC79C8  ";
        when(whitelistRepository.existsByAddressIgnoreCaseAndChainTypeAndStatus(
                TO_ADDRESS, ChainType.EVM, WhitelistStatus.ACTIVE))
                .thenReturn(true);

        PolicyDecision decision = rule.evaluate(req(mixedCaseAddress));

        assertThat(decision.allowed()).isTrue();
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    private CreateWithdrawalRequest req(String toAddress) {
        // CreateWithdrawalRequest의 compact constructor에서 toAddress=null을 차단하므로
        // null 시나리오는 DTO 레벨에서 처리됨 — PolicyRule 레벨에서는 테스트 불필요.
        // 최소 유효 주소(all-zeros)로 대체.
        String addr = (toAddress == null)
                ? "0x0000000000000000000000000000000000000000"
                : toAddress;
        return new CreateWithdrawalRequest("evm", "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266",
                addr, "ETH", new BigInteger("1"));
    }
}
