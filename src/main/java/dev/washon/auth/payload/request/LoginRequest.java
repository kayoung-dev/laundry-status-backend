package dev.washon.auth.payload.request;

/**
 * POST /admin/auth/login body. Field names mirror the original NestJS DTO
 * (apps/api/src/admin-auth/login.dto.ts).
 */
public record LoginRequest(String email, String password) {
}
