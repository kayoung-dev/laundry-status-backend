package dev.washon.auth.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Student session lifetimes ({@code washon.student.session.*}). The signing
 * secret and the cookie's Secure flag are shared with the admin session
 * ({@code washon.admin.session.secret} / {@code secure-cookie}); the student
 * key is derived from that secret, see {@link StudentSessionService}.
 *
 * @param ttl         token lifetime without "로그인 상태 유지"; the cookie itself ends with the browser session
 * @param rememberTtl token and cookie lifetime with "로그인 상태 유지"
 */
@ConfigurationProperties("washon.student.session")
public record StudentSessionProperties(
        @DefaultValue("1d") Duration ttl,
        @DefaultValue("30d") Duration rememberTtl) {
}
