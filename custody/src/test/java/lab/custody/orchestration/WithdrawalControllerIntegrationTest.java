package lab.custody.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WithdrawalControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;


    @Test
    void create_withValidChainType_returnsCreatedWithdrawalWithParsedChainType() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-bft-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "bft",
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xto",
                                  "asset": "USDC",
                                  "amount": 100
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainType").value("BFT"))
                .andExpect(jsonPath("$.status").value("W4_SIGNING"));
    }

    @Test
    void create_withInvalidChainType_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-invalid-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "unknown",
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xto",
                                  "asset": "USDC",
                                  "amount": 100
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid chainType: unknown"));
    }

    @Test
    void create_withoutChainType_defaultsToEvm() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-default-chain-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xto",
                                  "asset": "USDC",
                                  "amount": 100
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainType").value("EVM"))
                .andExpect(jsonPath("$.status").value("W4_SIGNING"));
    }

    @Test
    void create_withNonWhitelistedAddress_isRejectedAndAuditLogged() throws Exception {
        String response = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-policy-reject-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "0xfrom",
                                  "toAddress": "0xnot-allowed",
                                  "asset": "USDC",
                                  "amount": 100
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String withdrawalId = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/withdrawals/{id}/policy-audits", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].allowed").value(false))
                .andExpect(jsonPath("$[0].reason").value("TO_ADDRESS_NOT_WHITELISTED: 0xnot-allowed"));
    }
}
