package dev.washon.common.web;

import dev.washon.auth.application.StudentSessionPayload;
import dev.washon.auth.application.StudentSessionService;
import dev.washon.auth.domain.AuthenticatedStudent;
import dev.washon.auth.domain.StudentUser;
import dev.washon.auth.infrastructure.StudentUserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards student routes registered in {@link AdminWebConfig} (for now
 * {@code /auth/me}), the student twin of {@link AdminAuthInterceptor}: the
 * token comes from the {@code washon_session} cookie or a bearer header,
 * and the user is re-read from the database on every request. Anything
 * missing, expired or tampered is a 401.
 */
@Component
public class StudentAuthInterceptor implements HandlerInterceptor {

    private static final String STUDENT_ATTRIBUTE = StudentAuthInterceptor.class.getName() + ".student";

    private final StudentSessionService sessions;
    private final StudentUserRepository users;

    public StudentAuthInterceptor(StudentSessionService sessions, StudentUserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Let CORS preflights through; see AdminAuthInterceptor.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = extractToken(request);
        StudentSessionPayload payload = token != null ? sessions.verify(token).orElse(null) : null;
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid student session");
        }
        StudentUser user = users.findById(payload.sub()).orElse(null);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists");
        }
        request.setAttribute(STUDENT_ATTRIBUTE, AuthenticatedStudent.from(user));
        return true;
    }

    /** The student this interceptor resolved for the current request, if any. */
    public static Optional<AuthenticatedStudent> currentStudent(HttpServletRequest request) {
        return Optional.ofNullable((AuthenticatedStudent) request.getAttribute(STUDENT_ATTRIBUTE));
    }

    private static String extractToken(HttpServletRequest request) {
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
                if (StudentSessionService.COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
