package lab.custody.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "custody.chain.mode=mock")
@AutoConfigureMockMvc
class EvmWalletControllerMockModeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void evmEndpointsAreUnavailableInMockMode() throws Exception {
        mockMvc.perform(get("/evm/wallet"))
                .andExpect(status().isNotFound());
    }
}
