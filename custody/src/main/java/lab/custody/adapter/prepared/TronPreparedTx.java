package lab.custody.adapter.prepared;

/**
 * 20-1: TRON prepared transaction.
 *
 * <p>{@code rawDataHex} is the UTF-8 bytes of the JSON-serialised {@code raw_data} object,
 * hex-encoded.  This is what gets SHA-256 hashed and signed.
 * {@code signature} is the 65-byte secp256k1 signature (r‖s‖v), hex-encoded, returned by
 * {@link lab.custody.adapter.tron.TronSigner#signRaw(byte[])}.
 * {@code expirationMs} is the epoch-millisecond expiry embedded in the TRON raw_data object.
 */
public record TronPreparedTx(
        String rawDataHex,    // hex of UTF-8(JSON(raw_data))
        String signature,     // 65-byte sig hex (r‖s‖v)
        long expirationMs     // tx expiration epoch-ms
) implements PreparedTx {
}
