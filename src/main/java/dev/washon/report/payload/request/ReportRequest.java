package dev.washon.report.payload.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.washon.machine.domain.MachineState;

/**
 * POST /reports body submitted by an (anonymous) student device. Field names
 * mirror the original NestJS DTO's snake_case wire format
 * (apps/api/src/reports/report.dto.ts).
 */
public record ReportRequest(
        @JsonProperty("machine_id") String machineId,
        @JsonProperty("reported_state") MachineState reportedState,
        @JsonProperty("device_id") String deviceId) {
}
