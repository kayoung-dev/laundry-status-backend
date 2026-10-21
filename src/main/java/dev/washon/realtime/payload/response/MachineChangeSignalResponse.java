package dev.washon.realtime.payload.response;

/**
 * Wire body of the admin stream's {@code machine_changed} event: only which
 * machine moved and to which version. The dashboard re-reads
 * {@code GET /admin/machines} on receipt, so admin-only fields (override
 * owner, needs-check, camera-down) keep a single source of truth.
 */
public record MachineChangeSignalResponse(String machineId, int version) {
}
