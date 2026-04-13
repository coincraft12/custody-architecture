package lab.custody.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.TimeUnit;

/**
 * 5-4-3: Mock 어댑터 자동 확인 시나리오 통합 테스트.
 *
 * <p>custody.mock.auto-confirm-delay-ms=200으로 설정하여
 * broadcast 후 200ms 지연 뒤 ConfirmationTracker가 자동으로
 * W6_BROADCASTED → W7_INCLUDED → W10_COMPLETED 전이를 수행하는지 검증한다.
 *
 * <p>9-4-2: @SpringBootTest 유지 이유 — 전체 출금 워크플로우(생성→브로드캐스트→자동확인→정산)를
 * 컨트롤러부터 DB까지 end-to-end로 검증하는 통합 테스트.
 * EvmMockAdapter, ConfirmationTracker, LedgerService, NonceAllocator 등 다중 서비스가 협력하므로
 * @WebMvcTest/@DataJpaTest로 분리 불가.
 */
@SpringBootTest(properties = {
        "custody.chain.mode=mock",
        // 5-4-2: 200ms 지연 후 자동 확인 실행
        "custody.mock.auto-confirm-delay-ms=200",
        // 즉시 finalization (block count=0 → W8 즉시 전이)
        "custody.confirmation-tracker.finalization-block-count=0",
        "custody.confirmation-tracker.poll-interval-ms=50",
        "custody.confirmation-tracker.auto-start=true"
})
@AutoConfigureMockMvc
class MockAutoConfirmIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 5-4-3-1: 자동 확인 시나리오 — broadcast 후 자동으로 W10_COMPLETED까지 전이하는지 검증.
     *
     * <p>auto-confirm-delay-ms=200으로 설정했으므로 broadcast 후 최대 3초 내에
     * W10_COMPLETED 상태가 되어야 한다.
     */
    @Test
    void autoConfirm_afterBroadcast_transitionsToCompleted() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-auto-confirm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0x90f79bf6eb2c4f870365e785982e1f101e93b906",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "ETH",
                                  "amount": 1000000000000000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asText();

        // 자동 확인이 완료될 때까지 최대 5초 대기
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("W10_COMPLETED"));
                });
    }

    /**
     * 5-4-3-2: 자동 확인 시나리오 — TxAttempt가 INCLUDED 상태로 전이하는지 검증.
     */
    @Test
    void autoConfirm_afterBroadcast_attemptBecomesIncluded() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-auto-confirm-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0x15d34aaf54267db7d7c367839aaf71a00a2c6a65",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asText();

        // Attempt가 INCLUDED 상태로 전이할 때까지 대기
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    MvcResult attemptsResult = mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                            .andExpect(status().isOk())
                            .andReturn();
                    JsonNode attempts = objectMapper.readTree(attemptsResult.getResponse().getContentAsString());
                    assertThat(attempts.size()).isGreaterThan(0);
                    // canonical attempt가 INCLUDED 이상 상태여야 함
                    String attemptStatus = attempts.get(0).get("status").asText();
                    assertThat(attemptStatus).isIn("INCLUDED", "FINALIZED");
                });
    }

    /**
     * 5-4-3-3: auto-confirm 비활성 상태(delay=0)에서는 자동 전이가 발생하지 않는지 검증.
     *
     * <p>이 테스트는 별도 context(delay=0)가 필요하여 @Nested + @SpringBootTest로 분리된다.
     * 현재 context(delay=200)에서는 broadcast 후 W6_BROADCASTED 상태가 즉시 확인된다.
     */
    @Test
    void autoConfirm_initialStatusIsW6_Broadcasted() throws Exception {
        // broadcast 직후에는 W6_BROADCASTED 상태여야 한다
        // (auto-confirm은 200ms 후에 실행되므로 즉시 조회 시 W6)
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-auto-confirm-initial-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0x9965507d1a55bcc2695c58ba16fb37d819b0a4dc",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "ETH",
                                  "amount": 1000000000000000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asText();

        // broadcast 직후 즉시 조회 → W6_BROADCASTED
        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"));

        // 이후 auto-confirm으로 W10까지 전이됨을 확인 (비동기)
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("W10_COMPLETED"))
                );
    }
}
