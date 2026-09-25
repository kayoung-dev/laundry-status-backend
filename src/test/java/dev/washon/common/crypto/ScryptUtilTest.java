package dev.washon.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Cross-compatibility with the original Node implementation. Every fixture
 * below was produced by Node v23.9.0 with
 * {@code require("node:crypto").scryptSync(plain, salt, 64).toString("hex")}
 * -- i.e. exactly what apps/api/src/common/scrypt.util.ts#hashSecret does
 * after choosing its random salt (N=16384, r=8, p=1 are Node's defaults).
 * Never regenerate these from the Java side.
 */
class ScryptUtilTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            correct horse battery staple                         | 0f1e2d3c4b5a69788796a5b4c3d2e1f0 | 0fb5edb9a5a4b9fb3b1ed26bde0ad44ad289d5772b6ef11e11cd459d3aa5be1c7b477a8ba6c2935d9154da273bc0bf749929b1bb7aade708c2f08ae7aa422ba8
            dev_4f6a0c2e9b1d8a7f3c5e6b0d2a4f8c1e3b5d7a9f0c2e4b6d | a3c1e5f7092b4d6e8f10325476981abc | ab089dfd368266caf36b19b4b4ca87653f4aba685a438e992f05d25d13616d64d189b0502eb646e167ffdf6babe2991ec340ee57910e31bdc864edd18f53ee58
            éè 한글 unicode!                                      | deadbeefcafebabe0011223344556677 | 27e3104e152d16f94f365b6117a785a514589a346e165709c59bb433370e63b2d6c9c155d725f30bd8dca16ca2b4e3ed31a685845c6b2e469b4875529d86d6a3
            """)
    void deriveMatchesNodeScryptSyncByteForByte(String plain, String salt, String nodeHashHex) {
        assertThat(HexFormat.of().formatHex(ScryptUtil.derive(plain, salt))).isEqualTo(nodeHashHex);
    }

    @Test
    void verifiesAHashStoredByTheNodeApi() {
        String storedByNode = "0f1e2d3c4b5a69788796a5b4c3d2e1f0:"
                + "0fb5edb9a5a4b9fb3b1ed26bde0ad44ad289d5772b6ef11e11cd459d3aa5be1c7b477a8ba6c2935d9154da273bc0bf749929b1bb7aade708c2f08ae7aa422ba8";

        assertThat(ScryptUtil.verifySecret("correct horse battery staple", storedByNode)).isTrue();
        assertThat(ScryptUtil.verifySecret("correct horse battery staplE", storedByNode)).isFalse();
    }

    @Test
    void saltIsUsedAsUtf8StringNotHexDecoded() {
        // Guard against the tempting "fix" of hex-decoding the salt: that
        // yields different bytes and would not match Node.
        byte[] asNode = ScryptUtil.derive("x", "0f1e2d3c4b5a69788796a5b4c3d2e1f0");
        byte[] hexDecodedSalt = SCrypt.generate(
                "x".getBytes(StandardCharsets.UTF_8),
                HexFormat.of().parseHex("0f1e2d3c4b5a69788796a5b4c3d2e1f0"),
                16384, 8, 1, 64);
        assertThat(asNode).isNotEqualTo(hexDecodedSalt);
    }

    @Test
    void hashSecretRoundTripsInNodeFormat() {
        String stored = ScryptUtil.hashSecret("s3cret");

        assertThat(stored).matches("[0-9a-f]{32}:[0-9a-f]{128}");
        assertThat(ScryptUtil.verifySecret("s3cret", stored)).isTrue();
        assertThat(ScryptUtil.verifySecret("other", stored)).isFalse();
    }

    @Test
    void rejectsMalformedStoredHashes() {
        assertThat(ScryptUtil.verifySecret("s3cret", null)).isFalse();
        assertThat(ScryptUtil.verifySecret("s3cret", "nocolon")).isFalse();
        assertThat(ScryptUtil.verifySecret("s3cret", "abcd:")).isFalse();
        assertThat(ScryptUtil.verifySecret("s3cret", ":abcd")).isFalse();
        assertThat(ScryptUtil.verifySecret("s3cret", "abcd:zz")).isFalse();
        assertThat(ScryptUtil.verifySecret("s3cret", "abcd:abcd")).isFalse();
    }
}
