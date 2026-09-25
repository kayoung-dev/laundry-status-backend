package dev.washon.report.payload.response;

/**
 * Response body for GET /admin/machines/{machineId}/needs-check, matching
 * the original NestJS {@code NeedsCheckResult} shape.
 */
public record NeedsCheckResponse(String machineId, boolean needsCheck, long distinctDeviceCount,
        long windowMinutes) {
}
