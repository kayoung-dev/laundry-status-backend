package dev.washon.machine.application;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * State machine thresholds, ported from
 * apps/api/src/state-machine/state-machine.config.ts. Overridable (e.g.
 * {@code washon.state-machine.stale-unknown=1s} or env
 * {@code WASHON_STATE_MACHINE_STALE_UNKNOWN}) so tests can exercise the
 * delayed transitions without waiting a real minute; production runs on the
 * defaults below.
 *
 * @param staleUnknown    silence after which a machine's state is no longer trustworthy
 * @param cameraDown      longer silence meaning the camera itself is down (consumed by SB-007)
 * @param cameraDownSweep how often SB-007's camera-down sweep runs
 * @param minConfidence   frames below this OCR confidence are treated as noise
 */
@ConfigurationProperties("washon.state-machine")
public record StateMachineProperties(
        @DefaultValue("60s") Duration staleUnknown,
        @DefaultValue("5m") Duration cameraDown,
        @DefaultValue("30s") Duration cameraDownSweep,
        @DefaultValue("0.5") double minConfidence) {

    /**
     * Single source of truth for "is this machine's camera down", shared by
     * {@link CameraDownMonitor}'s sweep and the admin dashboard's live
     * {@code cameraDown} field (SB-007) so the two can never disagree about
     * what "down" means. {@code lastFrameAt == null} (never sent a frame) is
     * treated as not-down, matching the original NestJS behaviour.
     */
    public boolean isCameraDown(Instant lastFrameAt, Instant now) {
        return lastFrameAt != null && !Duration.between(lastFrameAt, now).minus(cameraDown).isNegative();
    }
}
