package lab.custody.adapter.tron;

import java.util.Arrays;

/**
 * 20-4: Utility class for TRON address conversions.
 *
 * <p>TRON addresses use Bitcoin's Base58Check encoding.
 * Decoded bytes: 21 bytes = 1-byte version (0x41) + 20-byte address body + 4-byte checksum.
 *
 * <p>For ABI encoding (TRC-20 transfer), we need the 20-byte EVM-compatible body
 * (strip the 0x41 version prefix).
 */
public final class TronAddressUtils {

    private TronAddressUtils() {}

    /**
     * Validate a TRON address.
     * TRON addresses (mainnet and all testnets) start with 'T' and are 34 characters long.
     *
     * @param address TRON base58 address
     * @return true if the address looks valid
     */
    public static boolean isValid(String address) {
        if (address == null) return false;
        return address.startsWith("T") && address.length() == 34;
    }

    /**
     * Convert a TRON base58 address to its 42-char hex representation (41 + 20 bytes).
     *
     * @param base58Address TRON base58check address (e.g. "TRX...")
     * @return 42-char hex string starting with "41"
     */
    public static String toHex(String base58Address) {
        byte[] decoded = Base58.decodeChecked(base58Address);
        // decoded = 21 bytes: version byte (0x41) + 20-byte body
        return bytesToHex(decoded);
    }

    /**
     * Extract the 20-byte EVM-compatible address body from a TRON address, as a hex string.
     *
     * <p>TRON uses the same secp256k1 key derivation as Ethereum; only the address encoding
     * differs (Base58Check with 0x41 prefix vs. 0x hex checksum).
     * This method strips the 0x41 version prefix so the result can be used directly in
     * ERC-20/TRC-20 ABI encoding.
     *
     * @param base58Address TRON base58check address
     * @return 40-char hex string (20 bytes)
     */
    public static String toEvmHex(String base58Address) {
        byte[] decoded = Base58.decodeChecked(base58Address);
        // Skip version byte (index 0 = 0x41), take 20-byte body (indices 1..20)
        byte[] body = Arrays.copyOfRange(decoded, 1, 21);
        return bytesToHex(body);
    }

    // ─── private helpers ───

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // ─── Minimal Base58Check decoder ───

    /**
     * Minimal Base58Check decoder (Bitcoin / TRON compatible).
     *
     * <p>Decodes the 25-byte payload (21-byte address + 4-byte checksum),
     * verifies the checksum, and returns the 21-byte address bytes.
     */
    static final class Base58 {

        private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        private static final int[] INDEXES = new int[128];

        static {
            Arrays.fill(INDEXES, -1);
            for (int i = 0; i < ALPHABET.length(); i++) {
                INDEXES[ALPHABET.charAt(i)] = i;
            }
        }

        private Base58() {}

        /**
         * Decode a Base58Check string and verify checksum.
         *
         * @param input Base58Check-encoded string
         * @return payload bytes without the 4-byte checksum (21 bytes for a TRON address)
         * @throws IllegalArgumentException if checksum is invalid
         */
        static byte[] decodeChecked(String input) {
            byte[] decoded = decode(input); // 25 bytes for TRON address
            if (decoded.length < 4) {
                throw new IllegalArgumentException("Base58Check input too short: " + input);
            }
            int payloadLen = decoded.length - 4;
            byte[] payload = Arrays.copyOf(decoded, payloadLen);
            byte[] checksum = Arrays.copyOfRange(decoded, payloadLen, decoded.length);
            byte[] expectedChecksum = Arrays.copyOf(sha256d(payload), 4);
            if (!Arrays.equals(checksum, expectedChecksum)) {
                throw new IllegalArgumentException("Invalid Base58Check checksum for: " + input);
            }
            return payload;
        }

        static byte[] decode(String input) {
            if (input.isEmpty()) return new byte[0];

            // Count leading '1's → leading zero bytes
            int zeros = 0;
            while (zeros < input.length() && input.charAt(zeros) == '1') {
                zeros++;
            }

            // Convert Base58 digits to a big-endian byte array
            byte[] buffer = new byte[input.length()];
            int outputLen = 0;
            for (int i = zeros; i < input.length(); i++) {
                char c = input.charAt(i);
                if (c >= 128 || INDEXES[c] == -1) {
                    throw new IllegalArgumentException("Invalid Base58 character: " + c);
                }
                int digit = INDEXES[c];

                // Multiply existing output by 58 and add digit
                int carry = digit;
                for (int j = buffer.length - 1; j >= 0; j--) {
                    carry += 58 * (buffer[j] & 0xff);
                    buffer[j] = (byte) (carry & 0xff);
                    carry >>= 8;
                }
                if (carry != 0) {
                    throw new IllegalArgumentException("Base58 decode overflow");
                }
                // Track rightmost non-zero position
                if (buffer[buffer.length - 1 - outputLen] != 0 || i == zeros) {
                    outputLen++;
                }
            }

            // Find first non-zero byte in buffer
            int firstNonZero = 0;
            while (firstNonZero < buffer.length && buffer[firstNonZero] == 0) {
                firstNonZero++;
            }

            // Combine leading zeros + payload
            byte[] result = new byte[zeros + (buffer.length - firstNonZero)];
            // leading zeros are already 0x00
            System.arraycopy(buffer, firstNonZero, result, zeros, buffer.length - firstNonZero);
            return result;
        }

        /** Double SHA-256 hash. */
        static byte[] sha256d(byte[] input) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                return md.digest(md.digest(input));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }
    }
}
