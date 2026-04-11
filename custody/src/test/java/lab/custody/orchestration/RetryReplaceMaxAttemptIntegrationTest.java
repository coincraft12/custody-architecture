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
 * RetryReplaceService 최대 시도 횟수(5회) 제한 통합 테스트.
 *
 * ensureWithinAttemptLimit(): attemptCount >= 5 이면 InvalidRequestException 발생.
 * 초기 1회 포함, 4번 재시도 → 총 5회. 5번째 재시도 → 400 Bad Request.
 *
 * 검증 시나리오:
 *  1. 4회 재시도까지 성공 (총 5 attempt)
 *  2. 5번째 재시도 → 400 Bad Request "max retry/replace attempts exceeded"
 */
@SpringBootTest(properties = "custody.chain.mode=mock")
@AutoConfigureMockMvc
class RetryReplaceMaxAttemptIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void retry_exceedsMaxAttemptLimit_returnsBadRequest() throws Exception {
        // 1. 출금 생성 (attemptNo=1)
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-max-attempt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xbcd4042de499d14e55001ccbb24a551f3b954096",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "ETH",
                                  "amount": 0.0001
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        // 2. 4번 재시도 (attemptNo=2,3,4,5) — 모두 성공해야 함
        for (int i = 2; i <= 5; i++) {
            mockMvc.perform(post("/withdrawals/{id}/retry", withdrawalId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.attemptNo").value(i));
        }

        // 3. attempt 이력 확인: 총 5개
        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));

        // 4. 5번째 재시도 시도 → 400 Bad Request
        mockMvc.perform(post("/withdrawals/{id}/retry", withdrawalId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("max retry/replace attempts exceeded (5)"));
    }

    @Test
    void replace_exceedsMaxAttemptLimit_returnsBadRequest() throws Exception {
        // 1. 출금 생성 (attemptNo=1)
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-max-replace-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0x71be63f3384f5fb98995aa9b7a22c58fc65ab84c",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "ETH",
                                  "amount": 0.0001
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        // 2. 4번 교체 (attemptNo=2,3,4,5) — 모두 성공해야 함
        for (int i = 2; i <= 5; i++) {
            mockMvc.perform(post("/withdrawals/{id}/replace", withdrawalId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.attemptNo").value(i));
        }

        // 3. attempt 이력 확인: 총 5개 (초기 1 + replace 4)
        // retry()와 replace() 모두 새 attempt를 생성하고 동일한 attempt 카운터를 공유.
        // ensureWithinAttemptLimit()은 retry()에서만 호출되므로, replace 후 retry 시 경계 조건이 발동.
        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));

        // retry 시도 → 총 attempt 5개 이상이므로 400 Bad Request
        mockMvc.perform(post("/withdrawals/{id}/retry", withdrawalId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("max retry/replace attempts exceeded (5)"));
    }
}
