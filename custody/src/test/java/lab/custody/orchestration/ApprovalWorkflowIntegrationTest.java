package lab.custody.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 10-2-4: 4-eyes 승인 워크플로 통합 테스트.
 *
 * <p>custody.approval.enabled=true 환경에서:
 * <ul>
 *   <li>첫 번째 승인 후에도 W2_APPROVAL_PENDING 유지 확인</li>
 *   <li>두 번째 승인 후 W3_APPROVED 전이 확인</li>
 *   <li>소액 출금 자동 승인 확인</li>
 *   <li>거부 시 W0_POLICY_REJECTED 전이 확인</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "custody.chain.mode=mock",
        "custody.approval.enabled=true",
        "custody.approval.low-risk-threshold-eth=0.5",
        "custody.approval.high-risk-threshold-eth=1.0"
})
@AutoConfigureMockMvc
class ApprovalWorkflowIntegrationTest {

    private static final String FROM = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    private static final String TO   = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 소액(< 0.5 ETH) 출금은 승인 워크플로 없이 즉시 W6_BROADCASTED.
     */
    @Test
    void smallAmount_autoApproved_reachesW6() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-approval-small-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "ETH",
                                  "amount": 100000000000000000
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"));
    }

    /**
     * 중금액(0.5 ≤ x < 1.0 ETH) 출금: 1인 승인 필요.
     * 첫 번째(유일) 승인 후 W3_APPROVED 전이 후 처리 완료(W6_BROADCASTED는 자동 진행 안 됨 — pending 상태 유지).
     */
    @Test
    void mediumAmount_requiresOneApproval_pendingUntilApproved() throws Exception {
        // 출금 생성 (0.6 ETH, 1인 승인 필요)
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-approval-medium-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "ETH",
                                  "amount": 600000000000000000
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W2_APPROVAL_PENDING"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asText();

        // 승인 태스크 확인
        mockMvc.perform(get("/withdrawals/{id}/approval-task", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requiredApprovals").value(1))
                .andExpect(jsonPath("$.approvedCount").value(0));

        // 단일 승인자 승인 → 태스크 APPROVED 전이
        mockMvc.perform(post("/withdrawals/{id}/approve", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverId\": \"approver-1\", \"reason\": \"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedCount").value(1));

        // 출금은 W3_APPROVED (브로드캐스트는 별도 트리거 필요)
        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W3_APPROVED"));
    }

    /**
     * 10-2-4: 고금액(≥ 1.0 ETH) 출금: 2인 승인 (4-eyes) 필요.
     * 첫 번째 승인 후에도 W2_APPROVAL_PENDING 유지.
     * 두 번째 승인 후 W3_APPROVED 전이.
     */
    @Test
    void highAmount_requires4Eyes_firstApprovalStillPending_secondApprovalCompletes() throws Exception {
        // 고금액 출금 생성 (2.0 ETH, 2인 승인 필요)
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-approval-4eyes-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "ETH",
                                  "amount": 2000000000000000000
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W2_APPROVAL_PENDING"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asText();

        // 승인 태스크 확인 (2인 필요)
        mockMvc.perform(get("/withdrawals/{id}/approval-task", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requiredApprovals").value(2))
                .andExpect(jsonPath("$.riskTier").value("HIGH"));

        // 첫 번째 승인 → PENDING 유지 (requiredApprovals=2)
        mockMvc.perform(post("/withdrawals/{id}/approve", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverId\": \"approver-1\", \"reason\": \"first approval\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))   // 아직 PENDING
                .andExpect(jsonPath("$.approvedCount").value(1));

        // 출금도 여전히 W2_APPROVAL_PENDING
        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W2_APPROVAL_PENDING"));

        // 두 번째 승인 → APPROVED
        mockMvc.perform(post("/withdrawals/{id}/approve", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverId\": \"approver-2\", \"reason\": \"second approval\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedCount").value(2));

        // 출금 W3_APPROVED
        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W3_APPROVED"));
    }

    /**
     * 거부 시 출금이 W0_POLICY_REJECTED로 전이.
     */
    @Test
    void reject_transitionsWithdrawalToPolicyRejected() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-approval-reject-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "ETH",
                                  "amount": 2000000000000000000
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W2_APPROVAL_PENDING"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asText();

        // 거부
        mockMvc.perform(post("/withdrawals/{id}/reject-approval", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverId\": \"approver-1\", \"reason\": \"suspicious\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // 출금 W0_POLICY_REJECTED
        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"));
    }

    /**
     * 동일 승인자 중복 승인 시 409/400 에러 반환.
     */
    @Test
    void duplicateApprover_returnsBadRequest() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-approval-dup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "ETH",
                                  "amount": 2000000000000000000
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isOk())
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asText();

        // 첫 번째 승인
        mockMvc.perform(post("/withdrawals/{id}/approve", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverId\": \"approver-1\"}"))
                .andExpect(status().isOk());

        // 동일 승인자 중복 → 에러
        mockMvc.perform(post("/withdrawals/{id}/approve", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverId\": \"approver-1\"}"))
                .andExpect(status().isBadRequest());
    }
}
