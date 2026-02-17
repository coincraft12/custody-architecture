package lab.custody.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(GlobalExceptionHandlerTest.TestConfig.class)
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void runtimeExceptionMessageIsSanitized() throws Exception {
        mockMvc.perform(get("/test-error").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.path").value("/test-error"))
                .andExpect(jsonPath("$.message").value("Failed with secret 0x[REDACTED]"));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        TestErrorController testErrorController() {
            return new TestErrorController();
        }
    }

    @RestController
    static class TestErrorController {
        @GetMapping("/test-error")
        String error() {
            throw new IllegalStateException("Failed with secret 0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        }
    }
}
