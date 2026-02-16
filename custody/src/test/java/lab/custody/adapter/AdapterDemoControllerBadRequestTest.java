package lab.custody.adapter;

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
class AdapterDemoControllerBadRequestTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsBadRequestWithAllowedTypesWhenChainTypeIsInvalid() throws Exception {
        mockMvc.perform(post("/adapter-demo/broadcast/abc")
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.allowedTypes[0]").value("EVM"))
                .andExpect(jsonPath("$.allowedTypes[1]").value("BFT"));
    }
}
