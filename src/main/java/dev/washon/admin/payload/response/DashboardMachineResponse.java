package dev.washon.admin.payload.response;

import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.MachineType;
import java.time.Instant;

/**
 * One GET /admin/machines row, matching the original NestJS
 * {@code DashboardMachine} shape (apps/api/src/admin/admin.service.ts),
 * including the {@code cameraDown}/{@code cameraDownSince} fields SB-007
 * adds. Both are computed live from {@code lastFrameAt} on every read (see
 * {@code AdminService.getDashboard}), so they auto-clear the moment a
 * fresh frame lands -- no persisted flag to go stale.
 */
public record DashboardMachineResponse(
        String machineId,
        String label,
        MachineType type,
        String roomId,
        String roomName,
        MachineState state,
        Integer remainingMin,
        String errorCode,
        boolean overrideActive,
        String overrideBy,
        boolean needsCheck,
        long reportCount,
        int version,
        Instant updatedAt,
        Instant lastFrameAt,
        boolean cameraDown,
        Instant cameraDownSince) {
}
