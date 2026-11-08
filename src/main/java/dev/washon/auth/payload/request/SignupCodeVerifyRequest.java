package dev.washon.auth.payload.request;

/** POST /auth/signup/code/verify body. */
public record SignupCodeVerifyRequest(String email, String code) {
}
