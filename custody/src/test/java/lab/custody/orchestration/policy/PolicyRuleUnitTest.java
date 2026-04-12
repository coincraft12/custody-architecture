package lab.custody.orchestration.policy;

import lab.custody.domain.policy.PolicyChangeRequestRepository;
import lab.custody.domain.whitelist.WhitelistAddressRepository;
import lab.custody.domain.whitelist.WhitelistStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.orchestration.CreateWithdrawalRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PolicyRule 단위 테스트.
 *
 * 검증 항목:
 *  AmountLimitPolicyRule
 *   1. 한도 초과 금액 → REJECT
 *   2. 한도와 동일한 금액 → ALLOW
 *   3. 한도 미만 금액 → ALLOW
 *
 *  ToAddressWhitelistPolicyRule
 *   4. ACTIVE 화이트리스트 주소 → ALLOW
 *   5. 화이트리스트 없는 주소 (DB 조회 false) → REJECT
 *
 *  PolicyEngine
 *   6. 첫 번째 REJECT 룰에서 단락평가(short-circuit) 후 나머지 룰 미실행
 *   7. 모든 룰이 ALLOW → ALLOW
 *   8. 룰이 없을 때 → ALLOW
 */
@ExtendWith(MockitoExtension.class)
class PolicyRuleUnitTest {

    // ── AmountLimitPolicyRule ───────────────────────────────────────────────

    @Test
    void amountLimit_amountExceedsMax_rejectsWithReason() {
        AmountLimitPolicyRule rule = new AmountLimitPolicyRule(new BigDecimal("100"));
        CreateWithdrawalRequest req = req("101");

        PolicyDecision decision = rule.evaluate(req);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("AMOUNT_LIMIT_EXCEEDED");
        assertThat(decision.reason()).contains("100");
        assertThat(decision.reason()).contains("101");
    }

    @Test
    void amountLimit_amountEqualsMax_allows() {
        AmountLimitPolicyRule rule = new AmountLimitPolicyRule(new BigDecimal("100"));
        CreateWithdrawalRequest req = req("100");

        PolicyDecision decision = rule.evaluate(req);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void amountLimit_amountBelowMax_allows() {
        AmountLimitPolicyRule rule = new AmountLimitPolicyRule(new BigDecimal("100"));
        CreateWithdrawalRequest req = req("0.0001");

        PolicyDecision decision = rule.evaluate(req);

        assertThat(decision.allowed()).isTrue();
    }

    // ── ToAddressWhitelistPolicyRule ────────────────────────────────────────

    @Mock
    private WhitelistAddressRepository whitelistRepository;

    @InjectMocks
    private ToAddressWhitelistPolicyRule whitelistRule;

    @Test
    void whitelist_activeAddress_allows() {
        when(whitelistRepository.existsByAddressIgnoreCaseAndChainTypeAndStatus(
                "0xto", ChainType.EVM, WhitelistStatus.ACTIVE))
                .thenReturn(true);

        PolicyDecision decision = whitelistRule.evaluate(req("1"));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void whitelist_nonWhitelistedAddress_rejectsWithReason() {
        when(whitelistRepository.existsByAddressIgnoreCaseAndChainTypeAndStatus(
                anyString(), any(), eq(WhitelistStatus.ACTIVE)))
                .thenReturn(false);

        PolicyDecision decision = whitelistRule.evaluate(req("1"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("TO_ADDRESS_NOT_WHITELISTED");
        assertThat(decision.reason()).contains("0xto");
    }

    @Test
    void whitelist_addressNormalization_lowercaseTrim() {
        // toAddress in request is mixed-case with spaces, rule normalizes before querying
        when(whitelistRepository.existsByAddressIgnoreCaseAndChainTypeAndStatus(
                eq("0xto"), any(), eq(WhitelistStatus.ACTIVE)))
                .thenReturn(true);

        CreateWithdrawalRequest req = new CreateWithdrawalRequest(
                "evm", "0xfrom", "  0XTO  ", "ETH", new BigDecimal("1"));
        PolicyDecision decision = whitelistRule.evaluate(req);

        assertThat(decision.allowed()).isTrue();
    }

    // ── PolicyEngine ────────────────────────────────────────────────────────

    @Mock
    private PolicyChangeRequestRepository changeRequestRepository;

    @Test
    void policyEngine_firstRejectingRule_shortCircuits() {
        PolicyRule alwaysReject = r -> PolicyDecision.reject("first-reject");
        PolicyRule shouldNotRun = mock(PolicyRule.class);
        PolicyEngine engine = new PolicyEngine(List.of(alwaysReject, shouldNotRun), changeRequestRepository);

        PolicyDecision decision = engine.evaluate(req("1"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("first-reject");
        verifyNoInteractions(shouldNotRun);
    }

    @Test
    void policyEngine_allAllow_returnsAllow() {
        PolicyRule rule1 = r -> PolicyDecision.allow();
        PolicyRule rule2 = r -> PolicyDecision.allow();
        PolicyEngine engine = new PolicyEngine(List.of(rule1, rule2), changeRequestRepository);

        PolicyDecision decision = engine.evaluate(req("1"));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void policyEngine_noRules_returnsAllow() {
        PolicyEngine engine = new PolicyEngine(List.of(), changeRequestRepository);

        PolicyDecision decision = engine.evaluate(req("1"));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void policyEngine_secondRuleRejects_returnsSecondReason() {
        PolicyRule alwaysAllow = r -> PolicyDecision.allow();
        PolicyRule alwaysReject = r -> PolicyDecision.reject("second-reject");
        PolicyEngine engine = new PolicyEngine(List.of(alwaysAllow, alwaysReject), changeRequestRepository);

        PolicyDecision decision = engine.evaluate(req("1"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("second-reject");
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    private CreateWithdrawalRequest req(String amount) {
        return new CreateWithdrawalRequest("evm", "0xfrom", "0xto", "ETH", new BigDecimal(amount));
    }
}
