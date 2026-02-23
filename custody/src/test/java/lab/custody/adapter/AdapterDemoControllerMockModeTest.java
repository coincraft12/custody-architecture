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

@SpringBootTest(properties = "custody.chain.mode=mock")
@AutoConfigureMockMvc
class AdapterDemoControllerMockModeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void evmBroadcastInMockMode_worksWithoutNonce() throws Exception {
        mockMvc.perform(post("/adapter-demo/broadcast/evm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "from": "0xfrom-demo",
                                  "to": "0xto-demo",
                                  "asset": "ETH",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.txHash").value(org.hamcrest.Matchers.startsWith("0xEVM_MOCK_")));
    }
}
