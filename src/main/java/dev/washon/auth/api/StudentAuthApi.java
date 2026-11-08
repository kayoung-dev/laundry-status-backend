package dev.washon.auth.api;

import dev.washon.auth.application.StudentAuthService;
import dev.washon.auth.application.StudentSessionService;
import dev.washon.auth.domain.AuthenticatedStudent;
import dev.washon.auth.payload.request.StudentLoginRequest;
import dev.washon.auth.payload.response.StudentLoginResponse;
import dev.washon.common.web.StudentAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Student login/logout/me. {@code /auth/me} is guarded by
 * {@link StudentAuthInterceptor} (registered in dev.washon.common.web).
 */
@RestController
@RequestMapping("/auth")
public class StudentAuthApi {

    private final StudentAuthService authService;
    private final StudentSessionService sessions;

    public StudentAuthApi(StudentAuthService authService, StudentSessionService sessions) {
        this.authService = authService;
        this.sessions = sessions;
    }

    /** Without remember the cookie lasts until the browser closes (the token itself expires sooner or later anyway). */
    @PostMapping("/login")
    public StudentLoginResponse login(@RequestBody StudentLoginRequest request, HttpServletResponse response) {
        StudentLoginResponse result = authService.login(request);
        Duration maxAge = request.remember() ? sessions.ttl(true) : null;
        response.addHeader(HttpHeaders.SET_COOKIE, sessions.cookie(result.token(), maxAge).toString());
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, sessions.cookie("", Duration.ZERO).toString());
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public AuthenticatedStudent me(HttpServletRequest request) {
        return StudentAuthInterceptor.currentStudent(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing student session"));
    }
}
