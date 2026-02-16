package lab.custody.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
                .andExpect(jsonPath("$.chainType").value("BFT"));
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
}
