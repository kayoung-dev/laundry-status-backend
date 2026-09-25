package dev.washon.machine.infrastructure;

import dev.washon.machine.domain.EventSource;
import dev.washon.machine.domain.MachineStateEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MachineStateEventRepository extends JpaRepository<MachineStateEvent, String> {

    Optional<MachineStateEvent> findByMachineIdAndFrameHash(String machineId, String frameHash);

    /** Frames that preceded {@code excludeId} (captured at or before {@code capturedAt}), newest first. */
    @Query("""
            SELECT e FROM MachineStateEvent e
             WHERE e.machineId = :machineId AND e.source = :source
               AND e.id <> :excludeId AND e.createdAt <= :capturedAt
             ORDER BY e.createdAt DESC, e.id DESC
            """)
    List<MachineStateEvent> findRecentOcrFrames(@Param("machineId") String machineId,
            @Param("source") EventSource source,
            @Param("excludeId") String excludeId,
            @Param("capturedAt") Instant capturedAt,
            Limit limit);

    /** Audit log view: one machine's events, newest first ({@code version} breaks same-millisecond ties). */
    @Query("""
            SELECT e FROM MachineStateEvent e
             WHERE e.machineId = :machineId
             ORDER BY e.createdAt DESC, e.version DESC, e.id DESC
            """)
    List<MachineStateEvent> findLatestByMachineId(@Param("machineId") String machineId, Limit limit);

    /** {@link #findLatestByMachineId} narrowed to one source (e.g. {@code admin_override}). */
    @Query("""
            SELECT e FROM MachineStateEvent e
             WHERE e.machineId = :machineId AND e.source = :source
             ORDER BY e.createdAt DESC, e.version DESC, e.id DESC
            """)
    List<MachineStateEvent> findLatestByMachineIdAndSource(@Param("machineId") String machineId,
            @Param("source") EventSource source, Limit limit);
}
