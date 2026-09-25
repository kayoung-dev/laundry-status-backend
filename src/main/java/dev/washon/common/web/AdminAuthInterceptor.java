package dev.washon.common.web;

import dev.washon.auth.application.SessionPayload;
import dev.washon.auth.application.SessionTokenService;
import dev.washon.auth.domain.AdminUser;
import dev.washon.auth.domain.AuthenticatedAdmin;
import dev.washon.auth.infrastructure.AdminUserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards the admin routes registered onto it in {@link AdminWebConfig}
 * ({@code /admin/auth/me} and {@code /admin/machines/**} -- login/logout must
 * stay reachable without a session). Ported from
 * apps/api/src/admin-auth/admin-auth.guard.ts: accepts the session token
 * from either the httpOnly cookie or an {@code Authorization: Bearer}
 * header, verifies its signature and expiry, then re-reads the AdminUser
 * from the database on every request -- the signature proves the session was
 * issued by us, the DB lookup proves the admin still exists (so a deleted
 * admin loses access immediately even with a still-valid signature). Missing,
 * expired or tampered tokens all fail the same way: a 401.
 *
 * <p>Reused by any later domain's admin routes (e.g. SB-006's admin
 * dashboard/override): add the route's path pattern in
 * {@link AdminWebConfig#addInterceptors}, and read the resolved identity via
 * {@link #currentAdmin(HttpServletRequest)}.
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String ADMIN_ATTRIBUTE = AdminAuthInterceptor.class.getName() + ".admin";

    private final SessionTokenService sessionTokenService;
    private final AdminUserRepository adminUsers;

    public AdminAuthInterceptor(SessionTokenService sessionTokenService, AdminUserRepository adminUsers) {
        this.sessionTokenService = sessionTokenService;
        this.adminUsers = adminUsers;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS preflight (SB-008): Spring still runs mapped interceptors for
        // a cross-origin OPTIONS preflight request, but a preflight carries
        // no session cookie/header, and the Fetch spec requires an "ok"
        // (2xx) preflight response for the browser to proceed with the real
        // request. Rejecting it here with 401 would silently break every
        // credentialed cross-origin call, so let it pass through -- the CORS
        // filter (dev.washon.common.web.AdminWebConfig#addCorsMappings)
        // already validated the origin before this runs.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = extractToken(request);
        SessionPayload payload = token != null ? sessionTokenService.verify(token).orElse(null) : null;
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid admin session");
        }

        AdminUser admin = adminUsers.findById(payload.sub()).orElse(null);
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin no longer exists");
        }

        request.setAttribute(ADMIN_ATTRIBUTE, AuthenticatedAdmin.from(admin));
        return true;
    }

    /** The admin this interceptor resolved for the current request, if any. */
    public static Optional<AuthenticatedAdmin> currentAdmin(HttpServletRequest request) {
        return Optional.ofNullable((AuthenticatedAdmin) request.getAttribute(ADMIN_ATTRIBUTE));
    }

    private String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String bearer = authorization.substring("Bearer ".length()).trim();
            if (!bearer.isEmpty()) {
                return bearer;
            }
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (SessionTokenService.COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
