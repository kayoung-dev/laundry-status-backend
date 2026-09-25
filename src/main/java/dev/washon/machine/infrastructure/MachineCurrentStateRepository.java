package dev.washon.machine.infrastructure;

import dev.washon.machine.domain.MachineCurrentState;
import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.WashStage;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Keyed by machine id (the table's primary key is machine_id). */
public interface MachineCurrentStateRepository extends JpaRepository<MachineCurrentState, String> {

    /**
     * Moves the materialized state only if nobody else bumped {@code version}
     * since it was read ({@code WHERE version = :expectedVersion}). Returns the
     * number of rows updated: 1 on success, 0 on a lost race.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE MachineCurrentState s
               SET s.state = :state, s.remainingMin = :remainingMin, s.errorCode = :errorCode,
                   s.washStage = :washStage,
                   s.version = :newVersion, s.lastFrameAt = :lastFrameAt, s.staleSince = :staleSince,
                   s.updatedAt = :updatedAt
             WHERE s.machineId = :machineId AND s.version = :expectedVersion
            """)
    int compareAndSetState(@Param("machineId") String machineId,
            @Param("expectedVersion") int expectedVersion,
            @Param("newVersion") int newVersion,
            @Param("state") MachineState state,
            @Param("remainingMin") Integer remainingMin,
            @Param("errorCode") String errorCode,
            @Param("washStage") WashStage washStage,
            @Param("lastFrameAt") Instant lastFrameAt,
            @Param("staleSince") Instant staleSince,
            @Param("updatedAt") Instant updatedAt);

    /**
     * Admin override counterpart of {@link #compareAndSetState}: same
     * {@code WHERE version = :expectedVersion} guard, but moves the override
     * flag instead of camera freshness ({@code lastFrameAt}/{@code staleSince}
     * are left untouched -- liveness is the camera's, not the admin's).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE MachineCurrentState s
               SET s.state = :state, s.remainingMin = :remainingMin, s.errorCode = :errorCode,
                   s.washStage = :washStage,
                   s.version = :newVersion, s.overrideActive = :overrideActive, s.overrideBy = :overrideBy,
                   s.updatedAt = :updatedAt
             WHERE s.machineId = :machineId AND s.version = :expectedVersion
            """)
    int compareAndSetOverride(@Param("machineId") String machineId,
            @Param("expectedVersion") int expectedVersion,
            @Param("newVersion") int newVersion,
            @Param("state") MachineState state,
            @Param("remainingMin") Integer remainingMin,
            @Param("errorCode") String errorCode,
            @Param("washStage") WashStage washStage,
            @Param("overrideActive") boolean overrideActive,
            @Param("overrideBy") String overrideBy,
            @Param("updatedAt") Instant updatedAt);
}
