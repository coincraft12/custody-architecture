package lab.custody.adapter.evm;

import lab.custody.adapter.Erc20TransferEncoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 18-1: Erc20TransferEncoder ABI 인코딩 단위 테스트.
 *
 * ERC-20 transfer(address,uint256) calldata 인코딩 결과를 검증한다:
 *   - 함수 selector: 0xa9059cbb
 *   - address 파라미터: 32바이트 left-zero-padded
 *   - uint256 파라미터: 32바이트 big-endian
 *   총 calldata: 4 + 32 + 32 = 68 bytes = "0x" + 136 hex chars
 */
class Erc20TransferEncoderTest {

    /**
     * USDC transfer(0x000000000000000000000000AbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCd1234, 1_000_000)
     * Known expected ABI encoding verified against Ethereum ABI spec.
     */
    @Test
    void encode_usdcTransfer_producesCorrectData() {
        String toAddress = "0xAbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCd1234";
        BigInteger amount = BigInteger.valueOf(1_000_000L); // 1 USDC (6 decimals)

        String result = Erc20TransferEncoder.encode(toAddress, amount);

        // total length: "0x" + 136 hex chars (68 bytes)
        assertThat(result).hasSize(2 + 136);
        assertThat(result).startsWith("0x");

        String hex = result.substring(2); // strip 0x

        // selector: a9059cbb
        assertThat(hex.substring(0, 8)).isEqualToIgnoringCase("a9059cbb");

        // address word: 12 zero bytes (24 hex chars) + 20-byte address (40 hex chars) = 64 hex chars
        String addressWord = hex.substring(8, 72);
        assertThat(addressWord.substring(0, 24)).isEqualTo("000000000000000000000000");
        assertThat(addressWord.substring(24)).isEqualToIgnoringCase("AbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCd1234");

        // amount word: 32 bytes big-endian = 0x00...000F4240 (1_000_000 = 0x0F4240)
        String amountWord = hex.substring(72, 136);
        assertThat(amountWord).isEqualToIgnoringCase(
                "00000000000000000000000000000000000000000000000000000000000f4240");
    }

    @Test
    void encode_stripsOxPrefix_fromInputAddress() {
        // With 0x prefix
        String withPrefix = Erc20TransferEncoder.encode("0xAbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCd1234", BigInteger.ONE);
        // Without 0x prefix
        String withoutPrefix = Erc20TransferEncoder.encode("AbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCdAbCd1234", BigInteger.ONE);

        assertThat(withPrefix).isEqualToIgnoringCase(withoutPrefix);
    }

    @Test
    void encode_zeroAddress_producesZeroPadded() {
        String result = Erc20TransferEncoder.encode("0x0000000000000000000000000000000000000000", BigInteger.TEN);

        String hex = result.substring(2);
        // address word should be all zeros (64 hex chars)
        assertThat(hex.substring(8, 72)).isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
    }

    @Test
    void encode_largeUsdt_amount() {
        // 1_000_000 USDT = 1_000_000 * 10^6 = 1_000_000_000_000 units
        BigInteger largeAmount = new BigInteger("1000000000000");
        String result = Erc20TransferEncoder.encode("0xdAC17F958D2ee523a2206206994597C13D831ec7", largeAmount);

        assertThat(result).startsWith("0x");
        assertThat(result).hasSize(2 + 136);

        String hex = result.substring(2);
        assertThat(hex.substring(0, 8)).isEqualToIgnoringCase("a9059cbb");

        // 1_000_000_000_000 = 0xE8D4A51000
        String amountWord = hex.substring(72, 136);
        assertThat(amountWord).isEqualToIgnoringCase(
                "000000000000000000000000000000000000000000000000000000e8d4a51000");
    }

    @Test
    void encode_outputLength_isAlways138Chars() {
        // "0x" + 136 hex chars regardless of address or amount values
        String r1 = Erc20TransferEncoder.encode("0x" + "ab".repeat(20), BigInteger.ONE);
        String r2 = Erc20TransferEncoder.encode("0x" + "00".repeat(20), BigInteger.ZERO);
        String r3 = Erc20TransferEncoder.encode("0x" + "ff".repeat(20), BigInteger.valueOf(Long.MAX_VALUE));

        assertThat(r1).hasSize(138);
        assertThat(r2).hasSize(138);
        assertThat(r3).hasSize(138);
    }

    @Test
    void encode_selectorIsAlwaysA9059cbb() {
        String result = Erc20TransferEncoder.encode("0x1234567890123456789012345678901234567890", BigInteger.valueOf(500_000L));
        assertThat(result.substring(2, 10)).isEqualToIgnoringCase("a9059cbb");
    }
}
