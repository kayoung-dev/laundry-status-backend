package dev.washon.auth.api;

import dev.washon.auth.application.AdminAuthService;
import dev.washon.auth.application.SessionTokenService;
import dev.washon.auth.domain.AuthenticatedAdmin;
import dev.washon.auth.payload.request.LoginRequest;
import dev.washon.auth.payload.response.LoginResponse;
import dev.washon.common.web.AdminAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ported from apps/api/src/admin-auth/admin-auth.controller.ts. Session
 * verification for {@code /admin/auth/me} is handled by
 * {@link AdminAuthInterceptor} (registered in dev.washon.common.web), not
 * here -- login and logout are the two admin routes that must stay reachable
 * without a session.
 */
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthApi {

    private final AdminAuthService adminAuthService;
    private final SessionTokenService sessionTokenService;

    public AdminAuthApi(AdminAuthService adminAuthService, SessionTokenService sessionTokenService) {
        this.adminAuthService = adminAuthService;
        this.sessionTokenService = sessionTokenService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request, HttpServletResponse response) {
        LoginResponse result = adminAuthService.login(request);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(result.token(), sessionTokenService.ttl()).toString());
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletResponse response) {
        // Sessions are stateless (SessionTokenService), so logging out means
        // dropping the cookie; any other copy of the token stays valid until
        // it expires.
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString());
        return Map.of("ok", true);
    }

    /** Used by the frontend to decide whether to render the dashboard or redirect to login. */
    @GetMapping("/me")
    public AuthenticatedAdmin me(HttpServletRequest request) {
        return AdminAuthInterceptor.currentAdmin(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing admin session"));
    }

    /**
     * httpOnly keeps the token out of reach of page scripts (so an XSS in the
     * dashboard cannot exfiltrate a session); SameSite=Lax is enough because
     * frontend/ and backend/ are same-site in dev/Phase 1 (also on the dev
     * server: dev.washon.site and api-dev.washon.site share washon.site).
     * Secure is on wherever the API is served over HTTPS
     * (washon.admin.session.secure-cookie, set by the dev profile).
     */
    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(SessionTokenService.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(sessionTokenService.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
