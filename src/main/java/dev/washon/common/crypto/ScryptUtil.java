package dev.washon.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;

/**
 * Salted scrypt hashing for the two secrets the API stores: per-camera API
 * keys and admin passwords. Only {@code <salt>:<hash>} (both hex) is stored.
 *
 * <p>Byte-compatible with the original Node implementation
 * ({@code apps/api/src/common/scrypt.util.ts}), which calls
 * {@code scryptSync(plain, saltHex, 64)} with Node's defaults. That means:
 * <ul>
 *   <li>N=16384, r=8, p=1, keylen=64 (Node's defaults);</li>
 *   <li>the password is the UTF-8 bytes of the plaintext string;</li>
 *   <li>the salt is the UTF-8 bytes of the <em>32-char hex string</em> --
 *       Node passes the string itself, it does NOT hex-decode it first.</li>
 * </ul>
 * Changing any of these silently breaks every stored hash; the
 * cross-compatibility test pins them against Node-generated fixtures.
 */
public final class ScryptUtil {

    static final int N = 16384;
    static final int R = 8;
    static final int P = 1;
    static final int KEY_LENGTH = 64;
    private static final int SALT_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private ScryptUtil() {
    }

    public static String hashSecret(String plain) {
        byte[] saltBytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(saltBytes);
        String salt = HEX.formatHex(saltBytes);
        return salt + ":" + HEX.formatHex(derive(plain, salt));
    }

    public static boolean verifySecret(String plain, String storedHash) {
        if (storedHash == null) {
            return false;
        }
        String[] parts = storedHash.split(":", -1);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return false;
        }
        byte[] expected;
        try {
            expected = HEX.parseHex(parts[1]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] candidate = derive(plain, parts[0]);
        if (candidate.length != expected.length) {
            return false;
        }
        return MessageDigest.isEqual(candidate, expected);
    }

    /** Raw scrypt with the Node-compatible parameters and salt encoding. */
    static byte[] derive(String plain, String salt) {
        return SCrypt.generate(
                plain.getBytes(StandardCharsets.UTF_8),
                salt.getBytes(StandardCharsets.UTF_8),
                N, R, P, KEY_LENGTH);
    }
}
