package lab.custody.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "custody.chain.mode=mock")
@AutoConfigureMockMvc
class WithdrawalControllerIntegrationTest {

    // Hardhat test addresses (valid 40-hex EVM format)
    private static final String FROM = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    private static final String TO   = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"; // whitelisted seed

    @Autowired
    private MockMvc mockMvc;


    @Test
    void create_withValidChainType_returnsCreatedWithdrawalWithParsedChainType() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-bft-1")
                        .header("X-Correlation-Id", "cid-withdrawal-create-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "bft",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "cid-withdrawal-create-001"))
                .andExpect(jsonPath("$.chainType").value("BFT"))
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"));
    }

    @Test
    void create_withoutCorrelationIdHeader_generatesResponseCorrelationId() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-generate-cid-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", not(isEmptyOrNullString())));
    }

    @Test
    void create_withInvalidChainType_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-invalid-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "unknown",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid chainType: unknown"));
    }

    @Test
    void create_withoutChainType_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-no-chain-type-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("chainType: chainType is required"));
    }

    @Test
    void create_normalizesAddressFieldsAtDtoBoundary() throws Exception {
        // Mixed-case + whitespace input → normalized to lowercase trimmed
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-normalize-address-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "  0xF39Fd6e51aad88F6F4ce6aB8827279cffFb92266  ",
                                  "toAddress": "  0x70997970C51812DC3A010C7D01B50E0D17DC79C8  ",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromAddress").value("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"))
                .andExpect(jsonPath("$.toAddress").value("0x70997970c51812dc3a010c7d01b50e0d17dc79c8"))
                .andExpect(jsonPath("$.status").value("W6_BROADCASTED"));
    }



    @Test
    void create_withSameIdempotencyKeyAndDifferentBody_returnsConflict() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-conflict-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-conflict-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "bft",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("same Idempotency-Key cannot be used with a different request body"));
    }

    @Test
    void create_withMalformedJson_returnsHelpfulBadRequestMessage() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-malformed-json-1")
                        .header("X-Correlation-Id", "cid-bad-json-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{'chainType':'evm','fromAddress':'%s','toAddress':'%s','asset':'USDC','amount':100}".formatted(FROM, TO)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", "cid-bad-json-001"))
                .andExpect(jsonPath("$.correlationId").value("cid-bad-json-001"))
                .andExpect(jsonPath("$.message").value(startsWith("Invalid JSON body. If you are using PowerShell, use double quotes for JSON (or send --data-binary from a file).")));
    }


    @Test
    void create_withoutIdempotencyHeader_returnsBadRequestWithHeaderHint() throws Exception {
        mockMvc.perform(post("/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, TO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required header: Idempotency-Key"));
    }

    @Test
    void create_withNonWhitelistedAddress_isRejectedAndAuditLogged() throws Exception {
        String notAllowed = "0xffffffffffffffffffffffffffffffffffffffff";
        String response = mockMvc.perform(post("/withdrawals")
                        .header("Idempotency-Key", "idem-policy-reject-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chainType": "evm",
                                  "fromAddress": "%s",
                                  "toAddress": "%s",
                                  "asset": "USDC",
                                  "amount": 1
                                }
                                """.formatted(FROM, notAllowed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W0_POLICY_REJECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String withdrawalId = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/withdrawals/{id}/policy-audits", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].allowed").value(false))
                .andExpect(jsonPath("$[0].reason").value("TO_ADDRESS_NOT_WHITELISTED: " + notAllowed));
    }
}
