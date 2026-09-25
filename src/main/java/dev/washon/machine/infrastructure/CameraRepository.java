package dev.washon.machine.infrastructure;

import dev.washon.machine.domain.Camera;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CameraRepository extends JpaRepository<Camera, String> {

    Optional<Camera> findByMachineId(String machineId);

    Optional<Camera> findByDeviceId(String deviceId);
}
