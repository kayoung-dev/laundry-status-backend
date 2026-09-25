package dev.washon.machine.application;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * One-off delayed transitions keyed per machine and kind, ported from
 * apps/api/src/state-machine/transition-scheduler.service.ts. Re-scheduling
 * atomically cancels and replaces the pending timer instead of stacking one.
 *
 * <p>Phase 1 scope: a single API process owns the state machine, so timers
 * live in memory and do not survive a restart.
 */
@Component
public class TransitionScheduler implements DisposableBean {

    /** {@code DONE}: done -> available after the grace period; {@code STALE}: no frames -> unknown. */
    public enum Kind { DONE, STALE }

    private static final Logger log = LoggerFactory.getLogger(TransitionScheduler.class);

    private final ThreadPoolTaskScheduler executor = new ThreadPoolTaskScheduler();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    public TransitionScheduler() {
        executor.setPoolSize(4);
        executor.setThreadNamePrefix("machine-transition-");
        executor.setRemoveOnCancelPolicy(true);
        executor.initialize();
    }

    public void schedule(String machineId, Kind kind, Duration delay, Runnable task) {
        String key = key(machineId, kind);
        timers.compute(key, (k, existing) -> {
            if (existing != null) {
                existing.cancel(false);
            }
            AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
            ScheduledFuture<?> future = executor.schedule(
                    () -> run(k, self, task), Instant.now().plus(delay));
            self.set(future);
            return future;
        });
    }

    public void cancel(String machineId, Kind kind) {
        timers.computeIfPresent(key(machineId, kind), (k, existing) -> {
            existing.cancel(false);
            return null;
        });
    }

    private void run(String key, AtomicReference<ScheduledFuture<?>> self, Runnable task) {
        // Deregister -- but only if this timer is still the registered one; a
        // replacement may already own the slot. computeIfPresent waits for the
        // scheduling compute() to finish, so self is always set by now.
        timers.computeIfPresent(key, (k, registered) -> registered == self.get() ? null : registered);
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("Scheduled transition {} failed", key, e);
        }
    }

    private static String key(String machineId, Kind kind) {
        return kind + ":" + machineId;
    }

    @Override
    public void destroy() {
        timers.values().forEach(future -> future.cancel(false));
        timers.clear();
        executor.shutdown();
    }
}
