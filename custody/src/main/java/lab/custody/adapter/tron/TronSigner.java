package lab.custody.adapter.tron;

import lab.custody.adapter.Signer;
import lab.custody.domain.withdrawal.ChainType;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * 20-5: TRON transaction signer using secp256k1 (same curve as EVM).
 *
 * <p>TRON signing differs from EVM in two ways:
 * <ol>
 *   <li>The hash function is SHA-256 (not Keccak-256).</li>
 *   <li>Addresses use Base58Check encoding with a 0x41 version prefix.</li>
 * </ol>
 *
 * <p>The signature format is identical to EVM: 65 bytes — r(32) ‖ s(32) ‖ v(1).
 *
 * <p>This bean is conditionally created by {@link TronAdapterConfig} when
 * {@code custody.tron.enabled=true}.
 */
public class TronSigner implements Signer {

    private final ECKeyPair keyPair;
    private final String address; // TRON base58check address

    /**
     * @param hexPrivateKey 64-char hex private key (no 0x prefix)
     */
    public TronSigner(String hexPrivateKey) {
        this.keyPair = ECKeyPair.create(Numeric.hexStringToByteArray(hexPrivateKey));
        this.address = deriveAddress(keyPair);
    }

    /**
     * Sign raw bytes using TRON's SHA-256 + secp256k1 scheme.
     *
     * @param rawDataBytes UTF-8 bytes of the JSON-serialised raw_data object
     * @return 65-byte signature: r(32) ‖ s(32) ‖ v(1)
     */
    @Override
    public byte[] signRaw(byte[] rawDataBytes) {
        // 1. SHA-256 hash (TRON uses SHA-256, not Keccak-256)
        byte[] hash = Hash.sha256(rawDataBytes);

        // 2. secp256k1 sign — false = do NOT prepend Ethereum personal-sign prefix
        Sign.SignatureData sig = Sign.signMessage(hash, keyPair, false);

        // 3. Concatenate r(32) + s(32) + v(1) = 65 bytes
        byte[] result = new byte[65];
        System.arraycopy(sig.getR(), 0, result, 0, 32);
        System.arraycopy(sig.getS(), 0, result, 32, 32);
        result[64] = sig.getV()[0];
        return result;
    }

    /** Not used for TRON — throws UnsupportedOperationException. */
    @Override
    @Deprecated
    public String sign(org.web3j.crypto.RawTransaction tx, long chainId) {
        throw new UnsupportedOperationException(
                "TronSigner does not support EVM RawTransaction signing. Use signRaw() instead.");
    }

    @Override
    public String getAddress() {
        return address;
    }

    @Override
    public ChainType getChainType() {
        return ChainType.TRON;
    }

    // ─── private helpers ───

    /**
     * Derive the TRON base58check address from an EC key pair.
     *
     * <p>Algorithm (same as Ethereum for key derivation, different for address encoding):
     * <ol>
     *   <li>Use web3j Keys.getAddress() to get the EVM hex address (last 20 bytes of Keccak-256
     *       of the uncompressed public key).</li>
     *   <li>Decode the 20-byte address body from hex.</li>
     *   <li>Prepend 0x41 (TRON mainnet version byte) → 21 bytes.</li>
     *   <li>Base58Check encode.</li>
     * </ol>
     */
    private static String deriveAddress(ECKeyPair keyPair) {
        // web3j Keys.getAddress() returns the last 20 bytes of Keccak-256(uncompressed pub key)
        // as a hex string (no 0x prefix), same derivation as TRON
        String evmHex = org.web3j.crypto.Keys.getAddress(keyPair.getPublicKey());
        byte[] addrBody = Numeric.hexStringToByteArray(evmHex);

        // Prepend TRON mainnet version byte (0x41)
        byte[] addrBytes = new byte[21];
        addrBytes[0] = 0x41;
        System.arraycopy(addrBody, 0, addrBytes, 1, 20);
        return base58CheckEncode(addrBytes);
    }

    private static String base58CheckEncode(byte[] payload) {
        // Compute checksum = first 4 bytes of SHA256(SHA256(payload))
        byte[] checksum = new byte[4];
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash1 = md.digest(payload);
            byte[] hash2 = md.digest(hash1);
            System.arraycopy(hash2, 0, checksum, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        byte[] input = new byte[payload.length + 4];
        System.arraycopy(payload, 0, input, 0, payload.length);
        System.arraycopy(checksum, 0, input, payload.length, 4);

        return encodeBase58(input);
    }

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private static String encodeBase58(byte[] input) {
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }

        byte[] copy = input.clone();
        char[] encoded = new char[copy.length * 2];
        int outputLen = 0;

        for (int i = zeros; i < copy.length; ) {
            int remainder = 0;
            for (int j = i; j < copy.length; j++) {
                int digit = (copy[j] & 0xff) + remainder * 256;
                copy[j] = (byte) (digit / 58);
                remainder = digit % 58;
            }
            encoded[outputLen++] = ALPHABET.charAt(remainder);

            // Advance past leading zeros in copy
            while (i < copy.length && copy[i] == 0) {
                i++;
            }
        }

        // Leading '1's for zero bytes
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < zeros; i++) {
            sb.append('1');
        }
        // Reverse the encoded characters
        for (int i = outputLen - 1; i >= 0; i--) {
            sb.append(encoded[i]);
        }
        return sb.toString();
    }
}
