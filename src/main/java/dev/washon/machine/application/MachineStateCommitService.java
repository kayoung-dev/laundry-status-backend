package dev.washon.machine.application;

import dev.washon.machine.domain.EventSource;
import dev.washon.machine.domain.MachineCurrentState;
import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.MachineStateEvent;
import dev.washon.machine.domain.OcrReading;
import dev.washon.machine.domain.StateDecision;
import dev.washon.machine.domain.WashStage;
import dev.washon.machine.infrastructure.MachineCurrentStateRepository;
import dev.washon.machine.infrastructure.MachineRepository;
import dev.washon.machine.infrastructure.MachineStateEventRepository;
import dev.washon.realtime.domain.MachineChangedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of the state machine: read current state, decide,
 * append the derived event and move the materialized row -- all in one
 * transaction.
 *
 * <p>Deliberately a separate bean from {@link MachineStateService}: that
 * class holds the per-machine Redis lock and calls in here through the Spring
 * proxy, so the lock is acquired before the transaction begins and released
 * only after it has committed. (Calling a {@code @Transactional} method on
 * {@code this} would bypass the proxy and silently run without a transaction.)
 * Callers must already hold the machine lock.
 *
 * <p>Never rewrites the raw OCR row: every derived transition is appended as a
 * separate {@code MachineStateEvent{source: system}}, and every admin
 * override set/clear as a {@code MachineStateEvent{source: admin_override}}.
 */
@Service
public class MachineStateCommitService {

    private final MachineCurrentStateRepository currentStates;
    private final MachineStateEventRepository events;
    private final StateMachineProperties properties;
    private final MachineRepository machines;
    private final ApplicationEventPublisher eventPublisher;

    public MachineStateCommitService(MachineCurrentStateRepository currentStates,
            MachineStateEventRepository events, StateMachineProperties properties,
            MachineRepository machines, ApplicationEventPublisher eventPublisher) {
        this.currentStates = currentStates;
        this.events = events;
        this.properties = properties;
        this.machines = machines;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void applyOcrFrame(String machineId, IngestedOcrFrame frame) {
        MachineCurrentState current = loadOrCreate(machineId);

        // Admin override wins over inference: keep tracking camera liveness,
        // but the displayed state is frozen.
        if (current.isOverrideActive()) {
            markFresh(current, frame);
            return;
        }

        StateDecision decision = StateDecision.decide(readingWindow(machineId, frame));
        if (decision == null || !isChange(current, decision)) {
            markFresh(current, frame);
            return;
        }

        commitTransition(current, decision, frame.capturedAt(), null);
    }

    /** Silence for the stale threshold -> unknown; staleSince records the last frame seen. */
    @Transactional
    public boolean demoteStale(String machineId) {
        MachineCurrentState current = currentStates.findById(machineId).orElse(null);
        if (current == null || current.isOverrideActive() || current.getState() == MachineState.unknown) {
            return false;
        }
        Instant staleSince = current.getLastFrameAt() != null ? current.getLastFrameAt() : Instant.now();
        commitTransition(current, new StateDecision(MachineState.unknown, null, null),
                current.getLastFrameAt(), staleSince);
        return true;
    }

    /**
     * Appends the derived transition and moves the materialized row, bumping
     * {@code version} so a lost update would show as a gap or repeat in the
     * event history. The {@code WHERE version = :old} guard is a second line
     * of defence behind the Redis lock (e.g. a lock whose TTL expired during a
     * GC pause): a mismatch aborts the whole transaction, event row included.
     */
    private void commitTransition(MachineCurrentState current, StateDecision next,
            Instant lastFrameAt, Instant staleSince) {
        int expectedVersion = current.getVersion();
        int newVersion = expectedVersion + 1;
        String machineId = current.getMachineId();
        Instant updatedAt = Instant.now();

        int updated = currentStates.compareAndSetState(machineId, expectedVersion, newVersion,
                next.state(), next.remainingMin(), next.errorCode(), next.stage(), lastFrameAt, staleSince,
                updatedAt);
        if (updated != 1) {
            throw concurrentWrite(machineId, expectedVersion);
        }

        appendAndPublish(machineId, EventSource.system, next.state(), next.remainingMin(), next.errorCode(),
                next.stage(), newVersion, null, updatedAt);
    }

    /**
     * Pins the machine to an admin-chosen state. remainingMin/errorCode are
     * cleared: they describe what the camera read, which no longer applies
     * once a human has taken the machine over. While the override is active,
     * {@link #applyOcrFrame} and {@link #demoteStale}
     * all leave the displayed state alone.
     *
     * @param auditDetail opaque free-form detail stored in the audit row's
     *        {@code raw_text} (who acted, and how) -- owned and decoded by the caller
     */
    @Transactional
    public MachineCurrentState commitOverride(String machineId, MachineState state, String overrideBy,
            String auditDetail) {
        MachineCurrentState current = loadOrCreate(machineId);
        return commitOverrideWrite(current, state, null, null, null, true, overrideBy, auditDetail);
    }

    /**
     * Releases the override and resets the display to {@code available} with
     * no remaining time, error code, or wash stage: clearing means "I
     * resolved it, it's free now," not "keep showing whatever the admin
     * overrode it to." The next qualifying OCR event resumes inference from
     * there as normal.
     */
    @Transactional
    public MachineCurrentState commitOverrideClear(String machineId, String auditDetail) {
        MachineCurrentState current = loadOrCreate(machineId);
        return commitOverrideWrite(current, MachineState.available, null, null, null, false, null, auditDetail);
    }

    /** Same version-guarded move + append + AFTER_COMMIT publish as {@link #commitTransition}, audited as admin_override. */
    private MachineCurrentState commitOverrideWrite(MachineCurrentState current, MachineState state,
            Integer remainingMin, String errorCode, WashStage washStage, boolean overrideActive, String overrideBy,
            String auditDetail) {
        int expectedVersion = current.getVersion();
        int newVersion = expectedVersion + 1;
        String machineId = current.getMachineId();
        Instant updatedAt = Instant.now();

        int updated = currentStates.compareAndSetOverride(machineId, expectedVersion, newVersion,
                state, remainingMin, errorCode, washStage, overrideActive, overrideBy, updatedAt);
        if (updated != 1) {
            throw concurrentWrite(machineId, expectedVersion);
        }

        appendAndPublish(machineId, EventSource.admin_override, state, remainingMin, errorCode, washStage,
                newVersion, auditDetail, updatedAt);
        // clearAutomatically on the update detached the stale entity; re-read the moved row.
        return currentStates.findById(machineId).orElseThrow();
    }

    private void appendAndPublish(String machineId, EventSource source, MachineState state, Integer remainingMin,
            String errorCode, WashStage washStage, int newVersion, String rawText, Instant updatedAt) {
        MachineStateEvent event = new MachineStateEvent();
        event.setMachineId(machineId);
        event.setSource(source);
        event.setState(state);
        event.setRemainingMin(remainingMin);
        event.setErrorCode(errorCode);
        event.setVersion(newVersion);
        event.setRawText(rawText);
        events.save(event);

        // Published now, but only actually dispatched to SSE listeners AFTER_COMMIT
        // (RoomEventBroker) -- a client must never observe a change this transaction
        // could still roll back.
        machines.findById(machineId).ifPresent(machine -> eventPublisher.publishEvent(
                new MachineChangedEvent(machine.getRoomId(), machineId, state, remainingMin,
                        errorCode, washStage, newVersion, updatedAt)));
    }

    private static IllegalStateException concurrentWrite(String machineId, int expectedVersion) {
        return new IllegalStateException("Concurrent state write detected for machine " + machineId
                + " (expected version " + expectedVersion + ")");
    }

    /**
     * The consensus window: the frame just ingested plus the frames that
     * preceded it, oldest first. Low-confidence frames are downgraded to noise
     * rather than dropped, so a burst of unreliable frames blocks a transition
     * instead of silently passing the window through.
     */
    private List<OcrReading> readingWindow(String machineId, IngestedOcrFrame frame) {
        List<MachineStateEvent> previous = events.findRecentOcrFrames(machineId, EventSource.ocr,
                frame.eventId(), frame.capturedAt(), Limit.of(StateDecision.COUNTDOWN_CONSENSUS_FRAMES - 1));

        List<OcrReading> window = new ArrayList<>(previous.size() + 1);
        for (MachineStateEvent row : previous) {
            window.add(toReading(row.getRawText(), row.getConfidence()));
        }
        Collections.reverse(window);
        window.add(toReading(frame.rawText(), frame.confidence()));
        return window;
    }

    private OcrReading toReading(String rawText, Double confidence) {
        if (confidence != null && confidence < properties.minConfidence()) {
            return new OcrReading.Noise();
        }
        return OcrReading.parse(rawText);
    }

    private static boolean isChange(MachineCurrentState current, StateDecision next) {
        return current.getState() != next.state()
                || !Objects.equals(current.getRemainingMin(), next.remainingMin())
                || !Objects.equals(current.getErrorCode(), next.errorCode())
                || current.getWashStage() != next.stage();
    }

    /** Freshness-only write (dirty-checked, no version bump): the machine is still reporting. */
    private static void markFresh(MachineCurrentState current, IngestedOcrFrame frame) {
        current.setLastFrameAt(frame.capturedAt());
        current.setStaleSince(null);
    }

    private MachineCurrentState loadOrCreate(String machineId) {
        return currentStates.findById(machineId).orElseGet(() -> {
            MachineCurrentState created = new MachineCurrentState();
            created.setMachineId(machineId);
            return currentStates.save(created);
        });
    }
}
