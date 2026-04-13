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
 * 9-2-2: 상태머신 전이 불변성 테스트.
 *
 * W10_COMPLETED 이후 POST /retry 시 적절한 에러 반환 확인.
 * W10_COMPLETED 이후 POST /replace 시 적절한 에러 반환 확인.
 */
@SpringBootTest(properties = "custody.chain.mode=mock")
@AutoConfigureMockMvc
class StateMachineInvarianceTest {

    private static final String FROM = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    private static final String TO   = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 9-2-2: W10_COMPLETED 이후 POST /retry → 에러 반환.
     *
     * TxAttempt가 INCLUDED/SUCCESS 상태이므로 retry 시
     * retry() 내부 canonical.transitionTo(FAILED_TIMEOUT)이 실패해야 한다.
     * (또는 attempt 상태 확인 로직에 의해 400 반환)
     */
    @Test
    void retry_afterW10Completed_returnsBadRequest() throws Exception {
        String withdrawalId = createAndCompleteWithdrawal("idem-sm-inv-retry-1");

        // W10_COMPLETED 이후 /retry 요청 — 에러 반환이어야 함
        mockMvc.perform(post("/withdrawals/{id}/retry", withdrawalId))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // 400 Bad Request 또는 500 Internal Server Error (불변성 위반)
                    assert status == 400 || status == 500
                            : "Expected error status after W10, got " + status;
                });
    }

    /**
     * W10_COMPLETED 이후 POST /replace → 에러 반환.
     * RetryReplaceService.replace()에서 INCLUDED 상태 체크로 400을 반환해야 한다.
     */
    @Test
    void replace_afterW10Completed_returnsBadRequest() throws Exception {
        String withdrawalId = createAndCompleteWithdrawal("idem-sm-inv-replace-1");

        // W10_COMPLETED 이후 /replace 요청 — 400 Bad Request 반환이어야 함
        mockMvc.perform(post("/withdrawals/{id}/replace", withdrawalId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Cannot replace attempt after it is already finalized on-chain. "
                        + "Create a new withdrawal/retry instead."));
    }

    /**
     * W10_COMPLETED 상태를 조회하면 정상적으로 status=W10_COMPLETED가 반환된다.
     */
    @Test
    void get_afterW10Completed_returnsCompletedStatus() throws Exception {
        String withdrawalId = createAndCompleteWithdrawal("idem-sm-inv-get-1");

        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W10_COMPLETED"));
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    /**
     * W6_BROADCASTED → W7_INCLUDED → W8_SAFE_FINALIZED → W9 → W10_COMPLETED 전이.
     */
    private String createAndCompleteWithdrawal(String idempotencyKey) throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "ETH",
                                  "amount": 100000000000000
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        JsonNode body = objectMapper.readTree(create.getResponse().getContentAsString());
        String withdrawalId = body.get("id").asText();

        // W6 → W7
        mockMvc.perform(post("/sim/withdrawals/{id}/confirm", withdrawalId))
                .andExpect(status().isOk());

        // W7 → W10 (via finalize)
        mockMvc.perform(post("/sim/withdrawals/{id}/finalize", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W10_COMPLETED"));

        return withdrawalId;
    }
}
