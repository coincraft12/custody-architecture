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
    void lab1_idempotencyAndStateFlow_preservesBusinessUnitAndSingleInitialAttempt() throws Exception {
        String requestBody = """
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
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W4_SIGNING"))
                .andReturn();

        MvcResult second = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab1-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W4_SIGNING"))
                .andReturn();

        String firstJson = first.getResponse().getContentAsString();
        String secondJson = second.getResponse().getContentAsString();

        JsonNode firstNode = objectMapper.readTree(firstJson);
        JsonNode secondNode = objectMapper.readTree(secondJson);

        String withdrawalId = firstNode.get("id").asText();
        assertThat(secondNode.get("id").asText()).isEqualTo(withdrawalId);

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attemptNo").value(1))
                .andExpect(jsonPath("$[0].canonical").value(true))
                .andExpect(jsonPath("$[0].status").value("A0_CREATED"));
    }

    @Test
    void lab2_retryReplaceSimulation_accumulatesAttemptsAndConvergesToIncluded() throws Exception {
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
                .andExpect(jsonPath("$.status").value("W4_SIGNING"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/sim/withdrawals/{id}/next-outcome/FAIL_SYSTEM", withdrawalId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/sim/withdrawals/{id}/broadcast", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"));

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].canonical").value(false))
                .andExpect(jsonPath("$[0].exceptionType").value("FAILED_SYSTEM"))
                .andExpect(jsonPath("$[1].canonical").value(true));

        mockMvc.perform(post("/sim/withdrawals/{id}/next-outcome/REPLACED", withdrawalId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/sim/withdrawals/{id}/broadcast", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"));

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].canonical").value(false))
                .andExpect(jsonPath("$[1].exceptionType").value("REPLACED"))
                .andExpect(jsonPath("$[2].canonical").value(true));

        mockMvc.perform(post("/sim/withdrawals/{id}/next-outcome/SUCCESS", withdrawalId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/sim/withdrawals/{id}/broadcast", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W7_INCLUDED"));

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].status").value("A4_INCLUDED"))
                .andExpect(jsonPath("$[2].canonical").value(true));
    }

    @Test
    void lab3_chainAdapters_areInvokedThroughSameOrchestratorCallShape() throws Exception {
        mockMvc.perform(post("/adapter-demo/broadcast/evm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "from": "a",
                                  "to": "b",
                                  "asset": "ETH",
                                  "amount": 10,
                                  "nonce": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.txHash").value(org.hamcrest.Matchers.startsWith("0x")));

        mockMvc.perform(post("/adapter-demo/broadcast/bft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "from": "a",
                                  "to": "b",
                                  "asset": "TOKEN",
                                  "amount": 10,
                                  "nonce": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.txHash").value(org.hamcrest.Matchers.startsWith("BFT_")));
    }

    @Test
    void lab4_policyEngine_rejectsAmountLimitAndLeavesAuditLog() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab4-amount-limit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xto",
                                  "asset": "USDC",
                                  "amount": 1001
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/withdrawals/{id}/policy-audits", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].allowed").value(false))
                .andExpect(jsonPath("$[0].reason").value("AMOUNT_LIMIT_EXCEEDED: max=1000, requested=1001"));
    }
}
