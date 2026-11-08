package dev.washon.auth.application;

import dev.washon.auth.domain.AuthenticatedStudent;
import dev.washon.auth.domain.StudentUser;
import dev.washon.auth.infrastructure.StudentUserRepository;
import dev.washon.auth.payload.request.StudentLoginRequest;
import dev.washon.auth.payload.response.StudentLoginResponse;
import dev.washon.common.crypto.ScryptUtil;
import dev.washon.common.redis.RateLimiterService;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Student login. An unknown email and a wrong password are the same 401;
 * each address gets {@link #ATTEMPTS} tries in a burst, then one more every
 * {@link #ATTEMPT_REFILL} (429 in between).
 */
@Service
public class StudentAuthService {

    static final int ATTEMPTS = 5;
    static final Duration ATTEMPT_REFILL = Duration.ofSeconds(12);

    private final StudentUserRepository users;
    private final StudentSessionService sessions;
    private final RateLimiterService rateLimiter;

    public StudentAuthService(StudentUserRepository users, StudentSessionService sessions,
            RateLimiterService rateLimiter) {
        this.users = users;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    public StudentLoginResponse login(StudentLoginRequest request) {
        if (!StringUtils.hasText(request.email()) || !StringUtils.hasText(request.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email and password must be non-empty strings");
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (!rateLimiter.tryConsume("student-login:" + email, ATTEMPTS, ATTEMPT_REFILL)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts; try again later");
        }

        StudentUser user = users.findByEmail(email).orElse(null);
        if (user == null || !ScryptUtil.verifySecret(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        Instant expiresAt = Instant.now().plus(sessions.ttl(request.remember()));
        String token = sessions.createToken(user.getId(), expiresAt);
        return new StudentLoginResponse(token, expiresAt, AuthenticatedStudent.from(user));
    }
}
