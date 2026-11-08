package dev.washon.auth.domain;

/**
 * The signed-in student, as {@code GET /auth/me} and {@code POST /auth/login}
 * return it; resolved per request by
 * {@code dev.washon.common.web.StudentAuthInterceptor}.
 */
public record AuthenticatedStudent(
        String id, String email, String name, String hall, String floor, String roomNumber, String defaultRoomId) {

    public static AuthenticatedStudent from(StudentUser user) {
        return new AuthenticatedStudent(user.getId(), user.getEmail(), user.getName(), user.getHall(),
                user.getFloor(), user.getRoomNumber(), user.getDefaultRoomId());
    }
}
