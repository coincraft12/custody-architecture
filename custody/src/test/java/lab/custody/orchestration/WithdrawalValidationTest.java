package lab.custody.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WithdrawalValidationTest {

    @Autowired
    MockMvc mockMvc;

    private static final String VALID_BODY = """
            {
              "chainType": "EVM",
              "fromAddress": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
              "toAddress": "0x1234567890123456789012345678901234567890",
              "asset": "ETH",
              "amount": 1
            }
            """;

    @Test
    void validRequest_passes() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void missingChainType_returns400() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAddress": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                                  "toAddress": "0x1234567890123456789012345678901234567890",
                                  "asset": "ETH",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]").value("chainType: chainType is required"));
    }

    @Test
    void invalidFromAddress_returns400() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "EVM",
                                  "fromAddress": "notanaddress",
                                  "toAddress": "0x1234567890123456789012345678901234567890",
                                  "asset": "ETH",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "EVM",
                                  "fromAddress": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                                  "toAddress": "0x1234567890123456789012345678901234567890",
                                  "asset": "ETH",
                                  "amount": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]").value("amount: amount must be greater than 0"));
    }

    @Test
    void negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "EVM",
                                  "fromAddress": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                                  "toAddress": "0x1234567890123456789012345678901234567890",
                                  "asset": "ETH",
                                  "amount": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void nullAmount_returns400() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "EVM",
                                  "fromAddress": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                                  "toAddress": "0x1234567890123456789012345678901234567890",
                                  "asset": "ETH",
                                  "amount": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void blankAsset_returns400() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "EVM",
                                  "fromAddress": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                                  "toAddress": "0x1234567890123456789012345678901234567890",
                                  "asset": "",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
