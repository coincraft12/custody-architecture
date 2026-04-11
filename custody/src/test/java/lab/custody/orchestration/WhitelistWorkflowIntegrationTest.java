package lab.custody.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lab.custody.orchestration.whitelist.WhitelistService;
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
 * 실습 7 — 화이트리스트 주소 등록 + 48h 보류 워크플로우 통합 테스트
 *
 * default-hold-hours=0 : approve() 시 activeAfter = now → 스케줄러가 즉시 ACTIVE 전환 가능
 * scheduler-delay-ms=999999999 : 스케줄러 자동 실행 억제 → 수동으로 promoteHoldingToActive() 호출
 *
 * 검증 시나리오:
 *  1. REGISTERED 상태 주소 → 출금 정책 거부
 *  2. approve() → HOLDING 상태 → 출금 여전히 거부
 *  3. 스케줄러 수동 실행 → ACTIVE 전환
 *  4. ACTIVE 상태 주소 → 출금 성공
 *  5. revoke() → REVOKED 상태 → 출금 다시 거부
 *  6. 중복 등록 → 400 Bad Request
 */
@SpringBootTest(properties = {
        "custody.chain.mode=mock",
        "custody.whitelist.default-hold-hours=0",       // 보류 0h → approvedAt == activeAfter (즉시 활성화 가능)
        "custody.whitelist.scheduler-delay-ms=999999999" // 스케줄러 자동 실행 억제
})
@AutoConfigureMockMvc
class WhitelistWorkflowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WhitelistService whitelistService;

    // 기존 정적 시드(0xto 등)에 없는 새 주소 사용
    static final String ADDR = "0xaaaa111122223333444455556666777788889999";

    @Test
    void lab7_fullWhitelistLifecycle_registeredToActiveToRevoked() throws Exception {

        // ── 1. 주소 등록 → REGISTERED ──────────────────────────────────────────
        MvcResult register = mockMvc.perform(post("/whitelist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "address": "%s",
                                  "chainType": "EVM",
                                  "registeredBy": "test-admin",
                                  "note": "lab7 테스트 주소"
                                }
                                """.formatted(ADDR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.address").value(ADDR))
                .andExpect(jsonPath("$.holdDurationHours").value(0))
                .andReturn();

        String whitelistId = id(register);

        // ── 2. REGISTERED 상태에서 출금 → 정책 거부 ────────────────────────────
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab7-step2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawal(ADDR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"));

        // ── 3. 승인 → HOLDING ──────────────────────────────────────────────────
        // hold-hours=0 이므로 activeAfter = approvedAt (즉시 활성화 가능 상태)
        mockMvc.perform(post("/whitelist/{id}/approve", whitelistId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "approvedBy": "test-admin" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HOLDING"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty())
                .andExpect(jsonPath("$.activeAfter").isNotEmpty());

        // ── 4. HOLDING 상태에서 출금 → 여전히 거부 ─────────────────────────────
        // DB 정책 룰은 status=ACTIVE 인 경우에만 허용
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab7-step4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawal(ADDR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"));

        // ── 5. 스케줄러 수동 실행 → HOLDING → ACTIVE ───────────────────────────
        // hold-hours=0 이므로 activeAfter <= now 조건 충족 → 즉시 ACTIVE 전환
        whitelistService.promoteHoldingToActive();

        mockMvc.perform(get("/whitelist/{id}", whitelistId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // ── 6. ACTIVE 상태에서 출금 → 성공 ────────────────────────────────────
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab7-step6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawal(ADDR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"));

        // ── 7. 취소 → REVOKED ──────────────────────────────────────────────────
        mockMvc.perform(post("/whitelist/{id}/revoke", whitelistId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revokedBy": "test-admin" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedBy").value("test-admin"));

        // ── 8. REVOKED 상태에서 출금 → 다시 거부 ──────────────────────────────
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab7-step8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawal(ADDR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"));
    }

    @Test
    void lab7_duplicateRegistration_returnsBadRequest() throws Exception {
        String addr = "0xbbbb111122223333444455556666777788889999";

        mockMvc.perform(post("/whitelist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "address": "%s",
                                  "chainType": "EVM",
                                  "registeredBy": "test-admin",
                                  "note": "첫 등록"
                                }
                                """.formatted(addr)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));

        // 동일 (address, chainType) 재등록 시 400
        mockMvc.perform(post("/whitelist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "address": "%s",
                                  "chainType": "EVM",
                                  "registeredBy": "test-admin",
                                  "note": "중복 등록 시도"
                                }
                                """.formatted(addr)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lab7_approveBeforeRegistered_returnsBadRequest() throws Exception {
        // 존재하지 않는 ID 승인 시도
        mockMvc.perform(post("/whitelist/{id}/approve", "00000000-0000-0000-0000-000000000099")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "approvedBy": "test-admin" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lab7_listByStatus_returnsFilteredEntries() throws Exception {
        String addr = "0xcccc111122223333444455556666777788889999";

        // 등록
        MvcResult reg = mockMvc.perform(post("/whitelist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "address": "%s",
                                  "chainType": "EVM",
                                  "registeredBy": "test-admin",
                                  "note": "목록 필터 테스트"
                                }
                                """.formatted(addr)))
                .andExpect(status().isOk())
                .andReturn();

        String wid = id(reg);

        // REGISTERED 필터 조회 → 포함 확인
        mockMvc.perform(get("/whitelist").param("status", "REGISTERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(wid)).value("REGISTERED"));

        // 승인 → HOLDING
        mockMvc.perform(post("/whitelist/{id}/approve", wid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "approvedBy": "test-admin" }
                                """))
                .andExpect(status().isOk());

        // HOLDING 필터 조회 → 포함 확인
        mockMvc.perform(get("/whitelist").param("status", "HOLDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(wid)).value("HOLDING"));
    }

    // ─── helper ──────────────────────────────────────────────────────────────

    private String id(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    private String withdrawal(String toAddress) {
        return """
                {
                  "chainType": "EVM",
                  "fromAddress": "0xfabb0ac9d68b0b445fb7357272ff202c5651694a",
                  "toAddress": "%s",
                  "asset": "ETH",
                  "amount": 0.0001
                }
                """.formatted(toAddress);
    }
}
