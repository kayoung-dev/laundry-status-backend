package dev.washon.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.common.crypto.HmacTokenCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Stateless student session tokens in {@link HmacTokenCodec}'s format,
 * signed with HMAC(admin secret, "washon-student-session") so a student
 * token is never accepted as an admin one (or the other way round). As with
 * admin sessions, logout only drops the cookie, and
 * {@code dev.washon.common.web.StudentAuthInterceptor} re-reads the user on
 * every request.
 */
@Component
public class StudentSessionService {

    public static final String COOKIE_NAME = "washon_session";

    private final HmacTokenCodec codec;
    private final StudentSessionProperties properties;
    private final boolean secureCookie;

    public StudentSessionService(AdminSessionProperties adminProperties, StudentSessionProperties properties,
            ObjectMapper objectMapper) {
        byte[] key = HmacTokenCodec.deriveKey(
                adminProperties.secret().getBytes(StandardCharsets.UTF_8), "washon-student-session");
        this.codec = new HmacTokenCodec(key, objectMapper);
        this.properties = properties;
        this.secureCookie = adminProperties.secureCookie();
    }

    public Duration ttl(boolean remember) {
        return remember ? properties.rememberTtl() : properties.ttl();
    }

    public String createToken(String userId, Instant expiresAt) {
        return codec.encode(new StudentSessionPayload(userId, expiresAt.toEpochMilli()));
    }

    /** Returns the payload, or empty if the token is malformed, mis-signed or expired. */
    public Optional<StudentSessionPayload> verify(String token) {
        return codec.decode(token, StudentSessionPayload.class)
                .filter(payload -> payload.sub() != null && payload.exp() > Instant.now().toEpochMilli());
    }

    /**
     * httpOnly + SameSite=Lax like the admin cookie (dev.washon.site and
     * api-dev.washon.site are same-site). {@code maxAge} null makes a
     * browser-session cookie; {@link Duration#ZERO} deletes it.
     */
    public ResponseCookie cookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/");
        if (maxAge != null) {
            builder.maxAge(maxAge);
        }
        return builder.build();
    }
}
