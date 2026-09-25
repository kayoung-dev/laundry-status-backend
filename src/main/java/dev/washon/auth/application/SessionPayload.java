package dev.washon.auth.application;

import dev.washon.auth.domain.AdminRole;

/**
 * The signed session payload, ported from session.util.ts's
 * {@code SessionPayload} interface ({@code sub}/{@code email}/{@code role}/
 * {@code exp}). {@code exp} is an epoch-millisecond expiry, matching the
 * original wire format.
 */
public record SessionPayload(String sub, String email, AdminRole role, long exp) {
}
