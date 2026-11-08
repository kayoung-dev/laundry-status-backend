package dev.washon.auth.application;

/** Signed student session payload: the user id and an epoch-millisecond expiry. */
public record StudentSessionPayload(String sub, long exp) {
}
