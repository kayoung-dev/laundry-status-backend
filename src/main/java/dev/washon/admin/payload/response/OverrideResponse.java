package dev.washon.admin.payload.response;

import dev.washon.machine.domain.MachineCurrentState;
import dev.washon.machine.domain.MachineState;
import java.time.Instant;

/** The machine's current state right after an override set/clear committed. */
public record OverrideResponse(
        String machineId,
        MachineState state,
        Integer remainingMin,
        String errorCode,
        boolean overrideActive,
        String overrideBy,
        int version,
        Instant updatedAt) {

    public static OverrideResponse from(MachineCurrentState current) {
        return new OverrideResponse(current.getMachineId(), current.getState(), current.getRemainingMin(),
                current.getErrorCode(), current.isOverrideActive(), current.getOverrideBy(), current.getVersion(),
                current.getUpdatedAt());
    }
}
