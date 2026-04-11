package lab.custody.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate Limiting 필터 통합 테스트.
 *
 * withdrawals-per-second=2, whitelist-per-second=2 설정으로
 * 3번째 요청부터 429가 반환되는지 검증.
 *
 * 각 테스트 메서드는 X-Forwarded-For로 고유 IP를 사용해 버킷을 격리한다.
 */
@SpringBootTest(properties = {
        "custody.chain.mode=mock",
        "custody.rate-limit.enabled=true",
        "custody.rate-limit.withdrawals-per-second=2",
        "custody.rate-limit.whitelist-per-second=2"
})
@AutoConfigureMockMvc
class RateLimitFilterTest {

    private static final String FROM = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    private static final String TO   = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Autowired
    MockMvc mockMvc;

    @Test
    void withdrawals_exceedsRateLimit_returns429() throws Exception {
        String ip = "10.0.1.1"; // test-local unique IP
        String body = """
                {
                  "chainType": "evm",
                  "fromAddress": "%s",
                  "toAddress": "%s",
                  "asset": "ETH",
                  "amount": 1
                }
                """.formatted(FROM, TO);

        // 1st — OK
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "rl-w-1")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 2nd — OK
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "rl-w-2")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 3rd — 429
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "rl-w-3")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("Too many requests. Please slow down."));
    }

    @Test
    void whitelist_exceedsRateLimit_returns429() throws Exception {
        String ip = "10.0.2.1"; // test-local unique IP

        // 각 요청마다 다른 주소 사용 (중복 등록 방지)
        String addr1 = "0xaa00000000000000000000000000000000000001";
        String addr2 = "0xaa00000000000000000000000000000000000002";
        String addr3 = "0xaa00000000000000000000000000000000000003";

        // 1st — OK
        mockMvc.perform(post("/whitelist")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "address": "%s", "chainType": "EVM", "registeredBy": "admin" }
                                """.formatted(addr1)))
                .andExpect(status().isOk());

        // 2nd — OK
        mockMvc.perform(post("/whitelist")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "address": "%s", "chainType": "EVM", "registeredBy": "admin" }
                                """.formatted(addr2)))
                .andExpect(status().isOk());

        // 3rd — 429
        mockMvc.perform(post("/whitelist")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "address": "%s", "chainType": "EVM", "registeredBy": "admin" }
                                """.formatted(addr3)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void getRequest_notRateLimited() throws Exception {
        // GET 요청은 rate limit 대상이 아님 — 여러 번 호출해도 429 없음
        String ip = "10.0.3.1";
        String withdrawalId = "00000000-0000-0000-0000-000000000001";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/withdrawals/{id}", withdrawalId)
                            .header("X-Forwarded-For", ip))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        // 200 or 500 (not found) — but NOT 429
                        assert status != 429 : "GET should never be rate limited";
                    });
        }
    }
}
