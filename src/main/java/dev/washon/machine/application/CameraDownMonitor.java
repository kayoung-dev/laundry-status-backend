package dev.washon.machine.application;

import dev.washon.machine.domain.MachineCurrentState;
import dev.washon.machine.infrastructure.MachineCurrentStateRepository;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Camera-down alert sweep (SB-007), ported from
 * apps/api/src/state-machine/camera-down-monitor.service.ts: once a machine
 * has had no new OCR frame for {@link StateMachineProperties#cameraDown()},
 * admins should be alerted.
 *
 * <p>The dashboard ({@code AdminService.getDashboard}) already computes
 * {@code cameraDown}/{@code cameraDownSince} live off
 * {@link MachineCurrentState#getLastFrameAt()} on every read, through the
 * same {@link StateMachineProperties#isCameraDown} used below -- so
 * detection and auto-clear for the dashboard badge need nothing from this
 * class. This component exists only to produce the "logged notification"
 * half of the story, and to log it exactly once per transition into
 * camera-down rather than on every tick while a machine stays down.
 *
 * <p>Design choice (same as the original): a periodic sweep over all
 * machines rather than a per-machine timer. A sweep is simpler (one query,
 * one schedule, independent of any one machine's timer state) and
 * self-heals after a process restart -- there is no per-machine timer state
 * to lose, so the very next sweep after a restart re-detects any machine
 * that is already camera-down.
 */
@Component
public class CameraDownMonitor {

    private static final Logger log = LoggerFactory.getLogger(CameraDownMonitor.class);

    /**
     * machineId -> lastFrameAt at the moment we logged it as camera-down, so
     * a repeat sweep does not log again while it stays down. Cleared on
     * recovery so a future down-episode for the same machine logs again.
     */
    private final ConcurrentHashMap<String, Instant> loggedDown = new ConcurrentHashMap<>();

    private final MachineCurrentStateRepository currentStates;
    private final StateMachineProperties properties;

    public CameraDownMonitor(MachineCurrentStateRepository currentStates, StateMachineProperties properties) {
        this.currentStates = currentStates;
        this.properties = properties;
    }

    /**
     * {@code fixedDelay} (not {@code fixedRate}): the next sweep is scheduled
     * only after the previous one finishes, so a slow run can never overlap
     * with another.
     */
    @Scheduled(fixedDelayString = "${washon.state-machine.camera-down-sweep:30s}")
    public void sweep() {
        Instant now = Instant.now();
        for (MachineCurrentState state : currentStates.findAll()) {
            String machineId = state.getMachineId();
            Instant lastFrameAt = state.getLastFrameAt();
            boolean isDown = properties.isCameraDown(lastFrameAt, now);
            boolean alreadyLogged = loggedDown.containsKey(machineId);

            if (isDown && !alreadyLogged) {
                loggedDown.put(machineId, lastFrameAt);
                log.warn("Camera down: machine {} has sent no OCR frame since {} (threshold {})", machineId,
                        lastFrameAt, properties.cameraDown());
            } else if (!isDown && alreadyLogged) {
                loggedDown.remove(machineId);
                log.info("Camera recovered: machine {} resumed sending frames", machineId);
            }
        }
    }
}
