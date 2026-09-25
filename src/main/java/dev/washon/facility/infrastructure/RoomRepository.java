package dev.washon.facility.infrastructure;

import dev.washon.facility.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, String> {
}
