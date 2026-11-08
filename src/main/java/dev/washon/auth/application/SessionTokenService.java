package dev.washon.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.auth.domain.AuthenticatedAdmin;
import dev.washon.common.crypto.HmacTokenCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Stateless admin session tokens, ported from
 * apps/api/src/admin-auth/session.util.ts, in {@link HmacTokenCodec}'s
 * format signed directly with the secret (student sessions,
 * {@link StudentSessionService}, sign with a key derived from it, so the two
 * never verify as each other). Byte-compatibility with the old Node tokens is not
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

    private final HmacTokenCodec codec;
    private final Duration ttl;
    private final boolean secureCookie;

    public SessionTokenService(AdminSessionProperties properties, ObjectMapper objectMapper) {
        this.codec = new HmacTokenCodec(properties.secret().getBytes(StandardCharsets.UTF_8), objectMapper);
        this.ttl = properties.ttl();
        this.secureCookie = properties.secureCookie();
    }

    public Duration ttl() {
        return ttl;
    }

    public boolean secureCookie() {
        return secureCookie;
    }

    public String createToken(AuthenticatedAdmin admin, Instant expiresAt) {
        return codec.encode(new SessionPayload(admin.id(), admin.email(), admin.role(), expiresAt.toEpochMilli()));
    }

    /** Returns the payload, or empty if the token is malformed, mis-signed or expired. */
    public Optional<SessionPayload> verify(String token) {
        return codec.decode(token, SessionPayload.class)
                .filter(payload -> payload.sub() != null && payload.exp() > Instant.now().toEpochMilli());
    }
}
