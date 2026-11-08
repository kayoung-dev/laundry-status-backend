package dev.washon.auth.payload.response;

import dev.washon.auth.domain.AuthenticatedStudent;
import java.time.Instant;

/** POST /auth/login body. The token also comes back here for non-browser clients (bearer). */
public record StudentLoginResponse(String token, Instant expiresAt, AuthenticatedStudent user) {
}
