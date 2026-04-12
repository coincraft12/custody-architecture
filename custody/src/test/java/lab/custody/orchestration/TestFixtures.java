package lab.custody.orchestration;

import lab.custody.orchestration.CreateWithdrawalRequest;

import java.math.BigDecimal;

/**
 * 9-4-3: 공통 테스트 픽스처 클래스.
 *
 * 반복되는 {@link CreateWithdrawalRequest} 빌더 코드를 통합한다.
 * Hardhat 테스트 계정 주소 상수와 일반적인 요청 팩토리 메서드를 제공한다.
 */
public final class TestFixtures {

    private TestFixtures() {}

    // ─── Hardhat Test Addresses ───────────────────────────────────────────

    /** Hardhat account #0 (from address) */
    public static final String ADDR_FROM = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";

    /** Hardhat account #1 (whitelisted seed address) */
    public static final String ADDR_TO_WHITELISTED = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    /** 화이트리스트에 없는 주소 */
    public static final String ADDR_NOT_WHITELISTED = "0xffffffffffffffffffffffffffffffffffffffff";

    /** Hardhat account #2 */
    public static final String ADDR_2 = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";

    /** Hardhat account #3 */
    public static final String ADDR_3 = "0x90f79bf6eb2c4f870365e785982e1f101e93b906";

    // ─── Request Factories ────────────────────────────────────────────────

    /**
     * 기본 EVM 출금 요청 생성.
     *
     * @param toAddress   수신 주소
     * @param amount      금액 (ETH)
     * @return CreateWithdrawalRequest
     */
    public static CreateWithdrawalRequest evmRequest(String toAddress, String amount) {
        return new CreateWithdrawalRequest(
                "evm", ADDR_FROM, toAddress, "USDC", new BigDecimal(amount));
    }

    /**
     * 기본 EVM 출금 요청 생성 (화이트리스트 주소, 1 USDC).
     */
    public static CreateWithdrawalRequest defaultEvmRequest() {
        return evmRequest(ADDR_TO_WHITELISTED, "1");
    }

    /**
     * EVM 출금 요청 생성 (fromAddress 지정 가능).
     */
    public static CreateWithdrawalRequest evmRequestFrom(String fromAddress, String toAddress, String amount) {
        return new CreateWithdrawalRequest(
                "evm", fromAddress, toAddress, "USDC", new BigDecimal(amount));
    }

    /**
     * BFT 출금 요청 생성.
     */
    public static CreateWithdrawalRequest bftRequest(String toAddress, String amount) {
        return new CreateWithdrawalRequest(
                "bft", ADDR_FROM, toAddress, "USDC", new BigDecimal(amount));
    }

    /**
     * JSON 형식의 기본 출금 요청 body 생성.
     *
     * @param chainType   체인 타입 ("evm", "bft")
     * @param fromAddress 발신 주소
     * @param toAddress   수신 주소
     * @param amount      금액
     */
    public static String withdrawalJson(String chainType, String fromAddress, String toAddress, String amount) {
        return """
                {
                  "chainType": "%s",
                  "fromAddress": "%s",
                  "toAddress": "%s",
                  "asset": "USDC",
                  "amount": %s
                }
                """.formatted(chainType, fromAddress, toAddress, amount);
    }

    /**
     * 기본 EVM 출금 JSON body (화이트리스트 주소, 1 USDC).
     */
    public static String defaultWithdrawalJson() {
        return withdrawalJson("evm", ADDR_FROM, ADDR_TO_WHITELISTED, "1");
    }
}
