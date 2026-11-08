package dev.washon.common.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code <base64url(payload JSON)>.<base64url(HMAC-SHA256 of that string)>}
 * tokens: a minimal JWT-shaped format signed with plain HMAC. Each kind of
 * session uses its own key, so a token of one kind never verifies as
 * another.
 */
public final class HmacTokenCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final ObjectMapper objectMapper;

    public HmacTokenCodec(byte[] key, ObjectMapper objectMapper) {
        this.key = key.clone();
        this.objectMapper = objectMapper;
    }

    /** HMAC-SHA256(secret, label): a separate key per session kind derived from one secret. */
    public static byte[] deriveKey(byte[] secret, String label) {
        return hmac(secret, label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String encode(Object payload) {
        try {
            String encodedPayload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            return encodedPayload + "." + sign(encodedPayload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create session token", e);
        }
    }

    /** The payload, or empty if the token is malformed or mis-signed. Expiry is the caller's check. */
    public <T> Optional<T> decode(String token, Class<T> type) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String encodedPayload = token.substring(0, dot);
        String signature = token.substring(dot + 1);

        byte[] expected;
        byte[] actual;
        try {
            expected = DECODER.decode(sign(encodedPayload));
            actual = DECODER.decode(signature);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(DECODER.decode(encodedPayload), type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String sign(String encodedPayload) {
        return ENCODER.encodeToString(hmac(key, encodedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
