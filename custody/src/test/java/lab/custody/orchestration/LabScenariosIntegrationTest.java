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

@SpringBootTest(properties = "custody.chain.mode=mock")
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
                  "fromAddress": "0x90f79bf6eb2c4f870365e785982e1f101e93b906",
                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                  "asset": "USDC",
                  "amount": 1
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
                                  "fromAddress": "0x15d34aaf54267db7d7c367839aaf71a00a2c6a65",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "USDC",
                                  "amount": 1
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
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("REPLACED"))
                .andExpect(jsonPath("$[1].canonical").value(false))
                .andExpect(jsonPath("$[2].canonical").value(true));
    }


    @Test
    void lab2_replaceAfterIncluded_returnsGuidanceMessage() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab2-included-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0x9965507d1a55bcc2695c58ba16fb37d819b0a4dc",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/sim/withdrawals/{id}/next-outcome/SUCCESS", withdrawalId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/sim/withdrawals/{id}/broadcast", withdrawalId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/withdrawals/{id}/replace", withdrawalId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot replace attempt after it is already finalized on-chain. Create a new withdrawal/retry instead."));
    }

    @Test
    void lab2_simNextOutcome_failSystem_replaced_success() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab2-sim-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0x976ea74026e726554db657fa54763abd0c3a0aa9",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "USDC",
                                  "amount": 1
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

    @Test
    void lab6_broadcastAndConfirmationTracks_areSeparated() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab6-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0x14dc79964da2c08b23698b3d3cc7ca32193d9955",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attemptNo").value(1))
                .andExpect(jsonPath("$[0].status").value("BROADCASTED"))
                .andExpect(jsonPath("$[0].canonical").value(true));

        mockMvc.perform(post("/sim/withdrawals/{id}/confirm", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.status").value("INCLUDED"));

        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W7_INCLUDED"));

        mockMvc.perform(get("/withdrawals/{id}/attempts", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attemptNo").value(1))
                .andExpect(jsonPath("$[0].status").value("INCLUDED"))
                .andExpect(jsonPath("$[0].canonical").value(true));
    }

    @Test
    void sync_inMockMode_marksAttemptAndWithdrawalIncluded() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab-sync-mock-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0x23618e81e3f5cdf7f54c3d65f7fbc0abf5b21e8f",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/withdrawals/{id}/sync?timeoutMs=0", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INCLUDED"));

        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W7_INCLUDED"));
    }

    @Test
    void lab8_fullStateMachineW7toW10() throws Exception {
        MvcResult create = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-lab8-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xa0ee7a142d267c1f36714e4a8f75612f20a79720",
                                  "toAddress": "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
                                  "asset": "ETH",
                                  "amount": 100000000000000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"))
                .andReturn();

        String withdrawalId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

        // W6 → W7: 온체인 포함 확인
        mockMvc.perform(post("/sim/withdrawals/{id}/confirm", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INCLUDED"));

        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W7_INCLUDED"));

        // W7 → W8 → W9 → W10: 최종 확정 + SETTLE 원장 기록
        mockMvc.perform(post("/sim/withdrawals/{id}/finalize", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W10_COMPLETED"));

        mockMvc.perform(get("/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W10_COMPLETED"));
    }
}
