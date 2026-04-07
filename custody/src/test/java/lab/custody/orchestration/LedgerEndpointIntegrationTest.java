package lab.custody.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 원장(Ledger) 엔드포인트 통합 테스트.
 *
 * GET /withdrawals/{id}/ledger 는 기존 테스트에서 전혀 검증되지 않은 엔드포인트.
 *
 * 검증 시나리오:
 *  1. W3_APPROVED 시점에 RESERVE 원장 기록 생성 → 출금 브로드캐스트 후 ledger에 RESERVE 엔트리 조회 가능
 *  2. W10_COMPLETED 시점에 SETTLE 원장 기록 생성 → finalize 후 SETTLE 엔트리 추가
 *  3. 존재하지 않는 출금 ID 조회 → 200 OK (빈 목록) — 엔드포인트가 출금 존재 여부를 검증하지 않음
 */
@SpringBootTest(properties = "custody.chain.mode=mock")
@AutoConfigureMockMvc
class LedgerEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void ledger_afterBroadcast_containsReserveEntry() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-ledger-reserve-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom-ledger",
                                  "toAddress": "0xto",
                                  "asset": "ETH",
                                  "amount": 0.0001
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/withdrawals/{id}/ledger", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("RESERVE"))
                .andExpect(jsonPath("$[0].asset").value("ETH"))
                .andExpect(jsonPath("$[0].withdrawalId").value(withdrawalId));
    }

    @Test
    void ledger_afterFinalize_containsReserveAndSettleEntries() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-ledger-settle-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom-ledger-settle",
                                  "toAddress": "0xto",
                                  "asset": "ETH",
                                  "amount": 0.0001
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        // W6 → W7 (온체인 포함)
        mockMvc.perform(post("/sim/withdrawals/{id}/confirm", withdrawalId))
                .andExpect(status().isOk());

        // W7 → W10 (최종 확정 + SETTLE 원장 기록)
        mockMvc.perform(post("/sim/withdrawals/{id}/finalize", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W10_COMPLETED"));

        mockMvc.perform(get("/withdrawals/{id}/ledger", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("RESERVE"))
                .andExpect(jsonPath("$[1].type").value("SETTLE"))
                .andExpect(jsonPath("$[1].asset").value("ETH"));
    }

    @Test
    void ledger_policyRejectedWithdrawal_returnsEmptyList() throws Exception {
        // 정책 거부된 출금(화이트리스트 없는 주소)은 원장 기록이 없어야 함
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-ledger-rejected-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xnot-whitelisted-ledger",
                                  "asset": "ETH",
                                  "amount": 0.0001
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/withdrawals/{id}/ledger", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void ledger_unknownWithdrawalId_returnsEmptyList() throws Exception {
        // /ledger 엔드포인트는 출금 존재 여부를 검증하지 않고 빈 목록을 반환
        mockMvc.perform(get("/withdrawals/{id}/ledger", "00000000-0000-0000-0000-000000000099"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
