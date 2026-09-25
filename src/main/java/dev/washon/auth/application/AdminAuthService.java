package dev.washon.auth.application;

import dev.washon.auth.domain.AdminUser;
import dev.washon.auth.domain.AuthenticatedAdmin;
import dev.washon.auth.infrastructure.AdminUserRepository;
import dev.washon.auth.payload.request.LoginRequest;
import dev.washon.auth.payload.response.LoginResponse;
import dev.washon.common.crypto.ScryptUtil;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ported from apps/api/src/admin-auth/admin-auth.service.ts. An unknown
 * email and a wrong password fail identically (both a plain 401), so the
 * response cannot be used to enumerate admin accounts.
 */
@Service
public class AdminAuthService {

    private final AdminUserRepository adminUsers;
    private final SessionTokenService sessionTokenService;

    public AdminAuthService(AdminUserRepository adminUsers, SessionTokenService sessionTokenService) {
        this.adminUsers = adminUsers;
        this.sessionTokenService = sessionTokenService;
    }

    public LoginResponse login(LoginRequest request) {
        if (!StringUtils.hasText(request.email()) || !StringUtils.hasText(request.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email and password must be non-empty strings");
        }

        AdminUser admin = adminUsers.findByEmail(request.email().toLowerCase()).orElse(null);
        if (admin == null || !ScryptUtil.verifySecret(request.password(), admin.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        Instant expiresAt = Instant.now().plus(sessionTokenService.ttl());
        AuthenticatedAdmin authenticatedAdmin = AuthenticatedAdmin.from(admin);
        String token = sessionTokenService.createToken(authenticatedAdmin, expiresAt);
        return new LoginResponse(token, expiresAt, authenticatedAdmin);
    }
}
