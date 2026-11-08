package dev.washon.auth.payload.request;

/**
 * POST /auth/login body (laundry-status-front features/auth/api.ts
 * {@code LoginRequest}). remember = "로그인 상태 유지".
 */
public record StudentLoginRequest(String email, String password, boolean remember) {
}
