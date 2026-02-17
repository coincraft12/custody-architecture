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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LabScenariosIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void lab1_sameIdempotencyKey_doesNotCreateSecondAttempt() throws Exception {
        String body = """
                {
                  "chainType": "evm",
                  "fromAddress": "0xfrom-lab1",
                  "toAddress": "0xto",
                  "asset": "USDC",
                  "amount": 100
                }
                """;

        MvcResult first = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab1-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab1-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        String withdrawalId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void lab2_retryAndReplace_updateCanonicalAttempt() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab2-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom-lab2",
                                  "toAddress": "0xto",
                                  "asset": "USDC",
                                  "amount": 50
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        JsonNode firstAttempt = objectMapper.readTree(mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                        .andReturn().getResponse().getContentAsString()).get(0);
        long firstNonce = firstAttempt.get("nonce").asLong();

        mockMvc.perform(post("/withdrawals/{id}/replace", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nonce").value(firstNonce));

        mockMvc.perform(post("/withdrawals/{id}/retry", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(3));

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REPLACED"))
                .andExpect(jsonPath("$[1].canonical").value(false))
                .andExpect(jsonPath("$[2].canonical").value(true));
    }

    @Test
    void lab2_simNextOutcome_failSystem_replaced_success() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab2-sim-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom-lab2-sim",
                                  "toAddress": "0xto",
                                  "asset": "USDC",
                                  "amount": 77
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/sim/withdrawals/{id}/next-outcome/FAIL_SYSTEM", withdrawalId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/sim/withdrawals/{id}/broadcast", withdrawalId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].canonical").value(false))
                .andExpect(jsonPath("$[0].exceptionType").value("FAILED_SYSTEM"))
                .andExpect(jsonPath("$[1].canonical").value(true));

        mockMvc.perform(post("/sim/withdrawals/{id}/next-outcome/REPLACED", withdrawalId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/sim/withdrawals/{id}/broadcast", withdrawalId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].canonical").value(false))
                .andExpect(jsonPath("$[1].status").value("REPLACED"))
                .andExpect(jsonPath("$[1].exceptionType").value("REPLACED"))
                .andExpect(jsonPath("$[2].canonical").value(true));

        mockMvc.perform(post("/sim/withdrawals/{id}/next-outcome/SUCCESS", withdrawalId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/sim/withdrawals/{id}/broadcast", withdrawalId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W7_INCLUDED"));

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].status").value("INCLUDED"));
    }
}
