package dev.washon.admin.payload.request;

/**
 * POST /admin/machines/{machineId}/override body. {@code state} stays a raw
 * string so an unknown or non-overridable value is a uniform 400 from
 * {@code AdminService}, mirroring apps/api/src/admin/override.dto.ts.
 */
public record OverrideRequest(String state) {
}
