package dev.washon.realtime.payload.response;

import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.MachineType;
import dev.washon.machine.domain.WashStage;
import java.time.Instant;

/** One machine's current state as reported in the SSE {@code snapshot} event. */
public record MachineSnapshotItem(
        String machineId,
        MachineType type,
        String label,
        MachineState state,
        Integer remainingMin,
        String errorCode,
        WashStage washStage,
        boolean overrideActive,
        int version,
        Instant updatedAt) {
}
