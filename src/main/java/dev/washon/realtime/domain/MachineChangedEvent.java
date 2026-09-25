package dev.washon.realtime.domain;

import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.WashStage;
import java.time.Instant;

/**
 * Fired once per committed {@code MachineCurrentState} transition (see
 * {@code MachineStateCommitService.commitTransition}, SB-002). Carries roomId
 * so the SSE gateway (SB-003) can filter to the room a client is watching
 * without a DB round trip per push.
 *
 * <p>Published as a plain Spring application event via
 * {@code ApplicationEventPublisher.publishEvent} -- it does not need to
 * extend {@code ApplicationEvent} for {@code @TransactionalEventListener} to
 * pick it up. Ported from apps/api/src/realtime/machine-events.emitter.ts.
 */
public record MachineChangedEvent(
        String roomId,
        String machineId,
        MachineState state,
        Integer remainingMin,
        String errorCode,
        WashStage washStage,
        int version,
        Instant updatedAt) {
}
