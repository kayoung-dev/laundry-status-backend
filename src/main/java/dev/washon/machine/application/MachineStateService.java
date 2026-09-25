package dev.washon.machine.application;

import dev.washon.common.redis.RedisLockService;
import dev.washon.machine.application.MachineStateCommitService.OcrFrameOutcome;
import dev.washon.machine.application.TransitionScheduler.Kind;
import dev.washon.machine.domain.MachineCurrentState;
import dev.washon.machine.domain.MachineState;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * Turns the raw OCR event stream into machine state. Every mutation for a
 * machine -- OCR-driven or timer-driven -- runs under a per-machine Redis
 * lock, making the machine a single-writer entity.
 *
 * <p>Lock wrapper around {@link MachineStateCommitService}, ported from
 * apps/api/src/state-machine/state-machine.service.ts.
 *
 * <p>Intentionally NOT {@code @Transactional}. Each mutation acquires the
 * {@code machine:<id>} Redis lock first and then calls the separately-proxied
 * commit bean, so the transaction begins and commits entirely inside the
 * lock. Timers are (re)armed after that commit, still under the lock.
 *
 * <p>Admin overrides (SB-006) go through this same wrapper and the same
 * {@code machine:<id>} lock, so an override and an in-flight OCR commit are
 * strictly serialized.
 */
@Service
public class MachineStateService {

    private final RedisLockService locks;
    private final MachineStateCommitService commits;
    private final TransitionScheduler scheduler;
    private final StateMachineProperties properties;

    /**
     * Monotonic per-machine frame counter. A pending staleness timer captures
     * the value it was scheduled with and stands down if a frame has landed
     * since -- clock-independent, unlike comparing camera-supplied timestamps.
     */
    private final ConcurrentHashMap<String, Long> frameSeq = new ConcurrentHashMap<>();

    public MachineStateService(RedisLockService locks, MachineStateCommitService commits,
            TransitionScheduler scheduler, StateMachineProperties properties) {
        this.locks = locks;
        this.commits = commits;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /**
     * Entry point called by ingestion for every newly accepted (non-duplicate)
     * frame. Must be called outside any transaction: the lock has to wrap the
     * commit, not sit inside it.
     */
    public void handleOcrFrame(String machineId, IngestedOcrFrame frame) {
        withMachineLock(machineId, () -> {
            long seq = frameSeq.merge(machineId, 1L, Long::sum);
            OcrFrameOutcome outcome = commits.applyOcrFrame(machineId, frame);
            if (outcome == OcrFrameOutcome.TRANSITIONED_TO_DONE) {
                scheduleDoneRelease(machineId);
            } else if (outcome == OcrFrameOutcome.TRANSITIONED) {
                scheduler.cancel(machineId, Kind.DONE);
            }
            scheduleStalenessCheck(machineId, seq);
        });
    }

    /**
     * Admin override: pins the machine to {@code state} under the same
     * per-machine lock as OCR-driven commits, so the two can never interleave.
     * {@code auditDetail} is stored verbatim on the {@code admin_override}
     * audit row. Must be called outside any transaction.
     */
    public MachineCurrentState setOverride(String machineId, MachineState state, String overrideBy,
            String auditDetail) {
        return callWithMachineLock(machineId, () -> commits.commitOverride(machineId, state, overrideBy, auditDetail));
    }

    /** Releases an admin override (same lock, same audit trail as {@link #setOverride}). */
    public MachineCurrentState clearOverride(String machineId, String auditDetail) {
        return callWithMachineLock(machineId, () -> commits.commitOverrideClear(machineId, auditDetail));
    }

    private void scheduleDoneRelease(String machineId) {
        scheduler.schedule(machineId, Kind.DONE, properties.doneToAvailable(),
                () -> withMachineLock(machineId, () -> commits.releaseDone(machineId)));
    }

    private void scheduleStalenessCheck(String machineId, long seq) {
        scheduler.schedule(machineId, Kind.STALE, properties.staleUnknown(), () -> withMachineLock(machineId, () -> {
            Long latest = frameSeq.get(machineId);
            if (latest == null || latest != seq) {
                return; // a frame landed while we waited for the lock
            }
            if (commits.demoteStale(machineId)) {
                scheduler.cancel(machineId, Kind.DONE);
            }
        }));
    }

    private void withMachineLock(String machineId, Runnable body) {
        callWithMachineLock(machineId, () -> {
            body.run();
            return null;
        });
    }

    private <T> T callWithMachineLock(String machineId, Supplier<T> body) {
        // The lock must wrap the commit, never sit inside a caller's transaction
        // (the commit would then only become visible after the lock is released).
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "Machine state mutations must be invoked outside a transaction");
        return locks.withLock("machine:" + machineId, body);
    }
}
