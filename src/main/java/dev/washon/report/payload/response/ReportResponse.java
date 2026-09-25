package dev.washon.report.payload.response;

import dev.washon.machine.domain.MachineState;
import java.time.Instant;

/** Response body for POST /reports, matching the original NestJS shape. */
public record ReportResponse(String id, String machineId, MachineState reportedState, String deviceId,
        Instant createdAt) {
}
