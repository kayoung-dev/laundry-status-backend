package dev.washon.machine.infrastructure;

import dev.washon.machine.domain.Machine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MachineRepository extends JpaRepository<Machine, String> {

    /** Ordered like the original Prisma query (orderBy createdAt asc) for a room's SSE snapshot. */
    List<Machine> findByRoomIdOrderByCreatedAtAsc(String roomId);

    /** Every machine, grouped by room, for the admin dashboard (original: orderBy roomId, createdAt). */
    List<Machine> findAllByOrderByRoomIdAscCreatedAtAsc();
}
