package dev.washon.auth.payload.request;

/**
 * POST /auth/signup body, in the frontend's camelCase shape
 * (laundry-status-front features/auth/api.ts {@code SignupRequest}).
 * floor / roomNumber are optional (null).
 */
public record SignupRequest(
        String email,
        String code,
        String password,
        String name,
        String hall,
        String floor,
        String roomNumber,
        String defaultRoomId,
        Agreements agreements) {

    /** Terms checked on the sign-up step 1; tos and privacy are required. */
    public record Agreements(boolean tos, boolean privacy, boolean push) {
    }
}
