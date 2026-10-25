package dev.washon.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.auth.domain.AuthenticatedAdmin;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Stateless admin session tokens, ported from
 * apps/api/src/admin-auth/session.util.ts. Format:
 * {@code <base64url(payload JSON)>.<base64url(HMAC-SHA256 of that string)>}
 * -- a minimal JWT-shaped token signed with plain HMAC, matching the
 * original's design. Byte-compatibility with the old Node tokens is not
 * required (an explicit earlier migration decision -- existing sessions are
 * simply reseeded); only this scheme's own internal consistency matters.
 *
 * <p>Sessions are stateless: logout only clears the cookie client-side, there
 * is no server-side revocation list, so a copy of a token stays valid until
 * it expires. {@code dev.washon.common.web.AdminAuthInterceptor} re-reads the
 * AdminUser from the database on every request, so deleting an admin revokes
 * access immediately even though the signature itself is still valid.
 */
@Component
public class SessionTokenService {

    /** Matches SESSION_COOKIE_NAME in the original session.util.ts. */
    public static final String COOKIE_NAME = "washon_admin_session";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String secret;
    private final Duration ttl;
    private final boolean secureCookie;
    private final ObjectMapper objectMapper;

    public SessionTokenService(AdminSessionProperties properties, ObjectMapper objectMapper) {
        this.secret = properties.secret();
        this.ttl = properties.ttl();
        this.secureCookie = properties.secureCookie();
        this.objectMapper = objectMapper;
    }

    public Duration ttl() {
        return ttl;
    }

    public boolean secureCookie() {
        return secureCookie;
    }

    public String createToken(AuthenticatedAdmin admin, Instant expiresAt) {
        SessionPayload payload = new SessionPayload(admin.id(), admin.email(), admin.role(), expiresAt.toEpochMilli());
        try {
            String encodedPayload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            return encodedPayload + "." + sign(encodedPayload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create admin session token", e);
        }
    }

    /** Returns the payload, or empty if the token is malformed, mis-signed or expired. */
    public Optional<SessionPayload> verify(String token) {
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

        SessionPayload payload;
        try {
            payload = objectMapper.readValue(DECODER.decode(encodedPayload), SessionPayload.class);
        } catch (Exception e) {
            return Optional.empty();
        }

        if (payload.sub() == null || payload.exp() <= Instant.now().toEpochMilli()) {
            return Optional.empty();
        }
        return Optional.of(payload);
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign admin session token", e);
        }
    }
}
