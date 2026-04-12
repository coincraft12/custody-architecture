package lab.custody.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 9-2-3: 정책 감사 로그 무결성 테스트.
 *
 * 출금 요청 거절 시 policy_audit_logs에 레코드가 정확히 1개만 존재하는지 확인.
 * 중복 기록이나 누락 없이 단일 감사 레코드가 생성되어야 한다.
 */
@SpringBootTest(properties = "custody.chain.mode=mock")
@AutoConfigureMockMvc
class PolicyAuditLogIntegrityTest {

    private static final String FROM = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    // 화이트리스트에 없는 주소
    private static final String NOT_WHITELISTED = "0xffffffffffffffffffffffffffffffffffffffff";

    @Autowired
    private MockMvc mockMvc;

    /**
     * 9-2-3: 정책 거절 시 policy_audit_logs에 레코드 1개만 존재.
     *
     * 동일한 출금 요청이 정책에 의해 거절되면 audit log는 정확히 1건만 생성된다.
     */
    @Test
    void policyRejected_auditLogContainsExactlyOneRecord() throws Exception {
        MvcResult result = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-audit-integrity-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, NOT_WHITELISTED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"))
                .andReturn();

        String withdrawalId = result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // audit log 조회: 정확히 1개
        mockMvc.perform(get("/withdrawals/{id}/policy-audits", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].allowed").value(false))
                .andExpect(jsonPath("$[0].reason").value(
                        "TO_ADDRESS_NOT_WHITELISTED: " + NOT_WHITELISTED));
    }

    /**
     * 동일한 멱등성 키로 두 번 요청해도 audit log는 1개만 있어야 한다 (멱등성 보장).
     */
    @Test
    void policyRejected_idempotentRequest_stillExactlyOneAuditRecord() throws Exception {
        String body = """
                {
                  "chainType": "evm",
                  "fromAddress": "%s",
                  "toAddress": "%s",
                  "asset": "USDC",
                  "amount": 1
                }
                """.formatted(FROM, NOT_WHITELISTED);

        // 첫 번째 요청
        MvcResult first = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-audit-integrity-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"))
                .andReturn();

        String withdrawalId = first.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // 두 번째 멱등성 요청 (동일 키, 동일 body)
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-audit-integrity-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // audit log는 여전히 1개만
        mockMvc.perform(get("/withdrawals/{id}/policy-audits", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * 정책 허용(ALLOW) 시에도 audit log가 1건 기록된다 (allowed=true).
     */
    @Test
    void policyAllowed_auditLogContainsExactlyOneAllowedRecord() throws Exception {
        String TO_WHITELISTED = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

        MvcResult result = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-audit-integrity-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, TO_WHITELISTED)))
                .andExpect(status().isOk())
                .andReturn();

        String withdrawalId = result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // audit log 조회: 정확히 1개 (allowed=true)
        mockMvc.perform(get("/withdrawals/{id}/policy-audits", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].allowed").value(true));
    }
}
