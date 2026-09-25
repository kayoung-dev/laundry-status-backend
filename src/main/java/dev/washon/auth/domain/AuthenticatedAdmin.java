package dev.washon.auth.domain;

/**
 * The identity {@code dev.washon.common.web.AdminAuthInterceptor} resolves
 * for a request, and what {@code POST /admin/auth/login} /
 * {@code GET /admin/auth/me} return to the caller. Mirrors
 * {@code apps/api/src/admin-auth/admin-request.ts}'s {@code AuthenticatedAdmin}.
 */
public record AuthenticatedAdmin(String id, String email, AdminRole role, String siteScopeId) {

    public static AuthenticatedAdmin from(AdminUser admin) {
        return new AuthenticatedAdmin(admin.getId(), admin.getEmail(), admin.getRole(), admin.getSiteScopeId());
    }
}
