package dev.washon.realtime.payload.response;

import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.WashStage;
import java.time.Instant;

/** Wire body of a {@code machine_changed} SSE event (roomId is filtering-only, not sent). */
public record MachineChangedResponse(
        String machineId,
        MachineState state,
        Integer remainingMin,
        String errorCode,
        WashStage washStage,
        int version,
        Instant updatedAt) {
}
