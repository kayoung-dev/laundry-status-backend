package dev.washon.auth.payload.response;

/** POST /auth/signup 201 body. */
public record SignupResponse(String id, String email, String name, String defaultRoomId) {
}
