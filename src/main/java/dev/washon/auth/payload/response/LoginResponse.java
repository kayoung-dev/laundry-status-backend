package dev.washon.auth.payload.response;

import dev.washon.auth.domain.AuthenticatedAdmin;
import java.time.Instant;

/**
 * POST /admin/auth/login response body. The token is also handed back here
 * (in addition to the cookie) so non-browser clients (scripts, curl, the
 * JUnit suite) can send it as a bearer token.
 */
public record LoginResponse(String token, Instant expiresAt, AuthenticatedAdmin admin) {
}
