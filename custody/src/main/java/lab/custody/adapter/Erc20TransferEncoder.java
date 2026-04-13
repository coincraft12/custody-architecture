package lab.custody.adapter;

import java.math.BigInteger;

/**
 * ERC-20 transfer(address,uint256) ABI 인코딩 유틸.
 *
 * <p>function selector: {@code 0xa9059cbb}
 * <ul>
 *   <li>인자 1: address — 32바이트, 오른쪽 정렬 (왼쪽 0 패딩)</li>
 *   <li>인자 2: uint256 — 32바이트, big-endian</li>
 * </ul>
 * 총 calldata 길이: 4 (selector) + 32 (address) + 32 (amount) = 68바이트 = 136 hex chars + "0x" prefix.
 */
public final class Erc20TransferEncoder {

    private static final String TRANSFER_FUNCTION_SELECTOR = "a9059cbb";

    private Erc20TransferEncoder() {}

    /**
     * ERC-20 transfer ABI 인코딩.
     *
     * @param toAddress 수신자 EVM 주소 (0x 포함 또는 미포함)
     * @param amount    전송량 (토큰 최소 단위, 예: USDC 100.0 → 100_000_000)
     * @return 0x 포함 hex 문자열 (전체 길이: "0x" + 136 chars)
     */
    public static String encode(String toAddress, BigInteger amount) {
        // 주소에서 0x / 0X 제거 후 40자로 정규화 (lowercase)
        String cleanAddress = ((toAddress.startsWith("0x") || toAddress.startsWith("0X"))
                ? toAddress.substring(2) : toAddress).toLowerCase(java.util.Locale.ROOT);

        // address: 32바이트(64 hex), 오른쪽 정렬 (왼쪽 0 패딩)
        // Note: %64s (no '0' flag) pads with spaces; replace(' ', '0') converts to zero-padding.
        // %064s would throw FormatFlagsConversionMismatchException since '0' flag is numeric-only.
        String paddedAddress = String.format("%64s", cleanAddress).replace(' ', '0');

        // uint256: 32바이트(64 hex), big-endian
        String paddedAmount = String.format("%64s", amount.toString(16)).replace(' ', '0');

        return "0x" + TRANSFER_FUNCTION_SELECTOR + paddedAddress + paddedAmount;
    }
}
