package lab.custody.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AmountLimitPolicyRule API 수준 통합 테스트.
 *
 * policy.max-amount=5 로 설정해 금액 한도 정책이 실제 출금 API에서 동작하는지 검증.
 *
 * 검증 시나리오:
 *  1. 한도 초과 금액 → W0_POLICY_REJECTED
 *  2. 한도와 동일한 금액 → W6_BROADCASTED (허용)
 *  3. 한도 미만 금액 → W6_BROADCASTED (허용)
 */
@SpringBootTest(properties = {
        "custody.chain.mode=mock",
        "policy.max-amount=5"
})
@AutoConfigureMockMvc
class AmountLimitPolicyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void create_amountExceedsLimit_isPolicyRejected() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-amount-limit-exceed-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xto",
                                  "asset": "ETH",
                                  "amount": 6
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"));
    }

    @Test
    void create_amountEqualsLimit_isAllowed() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-amount-limit-equal-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xto",
                                  "asset": "ETH",
                                  "amount": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"));
    }

    @Test
    void create_amountBelowLimit_isAllowed() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-amount-limit-below-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xto",
                                  "asset": "ETH",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"));
    }

    @Test
    void create_amountExceedsLimit_policyAuditLogsRejection() throws Exception {
        String response = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-amount-limit-audit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xto",
                                  "asset": "ETH",
                                  "amount": 7
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String withdrawalId = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isMethodNotAllowed()); // GET-only endpoint sanity check

        // policy-audits must record the rejection reason
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/withdrawals/{id}/policy-audits", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].allowed").value(false))
                .andExpect(jsonPath("$[0].reason").value(
                        org.hamcrest.Matchers.containsString("AMOUNT_LIMIT_EXCEEDED")));
    }
}
