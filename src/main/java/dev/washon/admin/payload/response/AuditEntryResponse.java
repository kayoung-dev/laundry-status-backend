package dev.washon.admin.payload.response;

import dev.washon.machine.domain.EventSource;
import dev.washon.machine.domain.MachineState;
import java.time.Instant;

/**
 * One GET /admin/machines/{machineId}/events row. {@code override} is
 * present only on {@code admin_override} rows.
 */
public record AuditEntryResponse(
        String id,
        String machineId,
        EventSource source,
        MachineState state,
        int version,
        Instant createdAt,
        OverrideAuditDetail override) {

    /** Who performed an override action; the row's createdAt is when. */
    public record OverrideAuditDetail(String action, String adminId, String adminEmail) {
    }
}
