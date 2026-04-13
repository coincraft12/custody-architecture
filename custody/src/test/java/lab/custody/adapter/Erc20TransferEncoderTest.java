package lab.custody.adapter;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 18-1: Erc20TransferEncoder 단위 테스트.
 *
 * 검증 항목:
 *  1. USDC 100 전송 (amount = 100_000_000, 6 decimals) — 알려진 ABI 인코딩 결과 검증
 *  2. 전체 calldata 길이 검증 (68바이트 = 136 hex chars + "0x" prefix)
 *  3. 0x 접두사 포함 주소 처리
 *  4. 0x 접두사 미포함 주소 처리 (동일 결과 반환)
 *  5. address 32바이트 패딩 검증
 *  6. uint256 32바이트 패딩 검증
 */
class Erc20TransferEncoderTest {

    // Known recipient address (Hardhat account #1)
    private static final String RECIPIENT = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
    // Normalised (lowercase, no 0x) for manual calculation
    private static final String RECIPIENT_HEX = "70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Test
    void encode_usdc100_matchesKnownAbiEncoding() {
        // USDC 100.0 → 100_000_000 (6 decimals)
        BigInteger amount = BigInteger.valueOf(100_000_000L);

        String calldata = Erc20TransferEncoder.encode(RECIPIENT, amount);

        // Expected structure:
        //   0xa9059cbb                                                     — selector (4 bytes)
        //   0000000000000000000000 70997970c51812dc3a010c7d01b50e0d17dc79c8 — address (32 bytes)
        //   0000000000000000000000000000000000000000000000000000000005f5e100 — uint256 100_000_000 (32 bytes)
        assertThat(calldata).startsWith("0x");
        assertThat(calldata).startsWith("0xa9059cbb");
        // Check address portion (chars 10..73 = 64 hex chars after selector)
        String addressPart = calldata.substring(10, 74);
        assertThat(addressPart).isEqualTo("000000000000000000000000" + RECIPIENT_HEX);
        // Check amount portion (chars 74..137 = 64 hex chars)
        String amountPart = calldata.substring(74, 138);
        assertThat(amountPart).isEqualTo("0000000000000000000000000000000000000000000000000000000005f5e100");
    }

    @Test
    void encode_totalCalldataLength_is68Bytes() {
        // 4 (selector) + 32 (address) + 32 (amount) = 68 bytes = 136 hex chars + "0x"
        String calldata = Erc20TransferEncoder.encode(RECIPIENT, BigInteger.ONE);

        assertThat(calldata).hasSize(2 + 136); // "0x" + 136
    }

    @Test
    void encode_addressWith0xPrefix_producesCorrectPadding() {
        String calldata = Erc20TransferEncoder.encode("0xabcdef0000000000000000000000000000000001", BigInteger.ONE);

        String addressPart = calldata.substring(10, 74);
        assertThat(addressPart).isEqualTo("000000000000000000000000abcdef0000000000000000000000000000000001");
    }

    @Test
    void encode_addressWithout0xPrefix_sameResultAsWithPrefix() {
        BigInteger amount = BigInteger.valueOf(1_000_000L);
        String withPrefix    = Erc20TransferEncoder.encode("0x" + RECIPIENT_HEX, amount);
        String withoutPrefix = Erc20TransferEncoder.encode(RECIPIENT_HEX, amount);

        assertThat(withPrefix).isEqualTo(withoutPrefix);
    }

    @Test
    void encode_selectorIsCorrect() {
        String calldata = Erc20TransferEncoder.encode(RECIPIENT, BigInteger.ONE);

        // Function selector for transfer(address,uint256) = keccak256("transfer(address,uint256)")[0:4]
        assertThat(calldata.substring(2, 10)).isEqualTo("a9059cbb");
    }

    @Test
    void encode_largeAmount_fitsIn32Bytes() {
        // Max uint256 — must not overflow padding
        BigInteger maxUint256 = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

        String calldata = Erc20TransferEncoder.encode(RECIPIENT, maxUint256);

        // Total length must still be "0x" + 136 hex chars
        assertThat(calldata).hasSize(138);
        // Amount part must be 64 hex chars
        String amountPart = calldata.substring(74);
        assertThat(amountPart).hasSize(64);
        assertThat(amountPart).isEqualTo("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
    }
}
