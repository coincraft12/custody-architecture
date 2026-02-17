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

}
