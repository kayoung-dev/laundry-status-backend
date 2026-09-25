package dev.washon.auth.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Admin session token configuration, ported from
 * apps/api/src/admin-auth/session.util.ts (ADMIN_SESSION_SECRET,
 * SESSION_TTL_MS). The secret has a non-production-safe default so local/dev
 * and tests boot without extra setup; any real deployment must override it
 * via {@code WASHON_ADMIN_SESSION_SECRET} (Spring relaxed binding) or
 * {@code washon.admin.session.secret}. Managing this secret through a real
 * secrets store is an explicit Phase-1-acceptable gap (plan section 4.7).
 *
 * @param secret HMAC-SHA256 signing key for session tokens
 * @param ttl    how long an issued session stays valid
 */
@ConfigurationProperties("washon.admin.session")
public record AdminSessionProperties(
        @DefaultValue("washon-dev-admin-session-secret-CHANGE-IN-PRODUCTION") String secret,
        @DefaultValue("12h") Duration ttl) {
}
