package lab.custody.orchestration.whitelist;

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
class RegisterAddressValidationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void validRequest_passes() throws Exception {
        mockMvc.perform(post("/whitelist")
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "address": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                                  "chainType": "EVM",
                                  "registeredBy": "admin"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void invalidAddress_returns400() throws Exception {
        mockMvc.perform(post("/whitelist")
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "address": "notanaddress",
                                  "chainType": "EVM",
                                  "registeredBy": "admin"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void missingAddress_returns400() throws Exception {
        mockMvc.perform(post("/whitelist")
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "EVM",
                                  "registeredBy": "admin"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]").value("address: address is required"));
    }

    @Test
    void missingRegisteredBy_returns400() throws Exception {
        mockMvc.perform(post("/whitelist")
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "address": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                                  "chainType": "EVM"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]").value("registeredBy: registeredBy is required"));
    }

    @Test
    void noteTooLong_returns400() throws Exception {
        String longNote = "a".repeat(256);
        mockMvc.perform(post("/whitelist")
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "address": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                                  "chainType": "EVM",
                                  "registeredBy": "admin",
                                  "note": "%s"
                                }
                                """.formatted(longNote)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]").value("note: note must be 255 characters or less"));
    }
}
