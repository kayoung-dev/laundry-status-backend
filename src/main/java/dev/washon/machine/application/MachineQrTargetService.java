package dev.washon.machine.application;

import dev.washon.machine.domain.Machine;
import dev.washon.machine.infrastructure.MachineRepository;
import dev.washon.machine.payload.response.MachineQrTargetResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Resolves a stable, machine-specific QR target for P2-001. Rather than a new
 * dedicated page, the target is a deep link into the existing no-login
 * student room view (frontend/app/room/[roomId]/page.tsx) with a
 * {@code machine} query param identifying which card to highlight -- see
 * that file's room-fetch/highlight logic for the frontend half of this
 * story. This keeps the QR surface URL-only (no rendered image), which is
 * both the simplest option and the easiest to verify: the target is
 * deterministic from {@code machineId} alone (no random id/token is ever
 * generated or stored), so scanning the same sticker always resolves to the
 * same URL.
 */
@Service
public class MachineQrTargetService {

    private final MachineRepository machines;
    private final String frontendOrigin;

    public MachineQrTargetService(
            MachineRepository machines,
            @Value("${washon.frontend.base-url:http://localhost:3000}") String frontendOrigin) {
        this.machines = machines;
        this.frontendOrigin = frontendOrigin;
    }

    public MachineQrTargetResponse getQrTarget(String machineId) {
        Machine machine = machines.findById(machineId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Machine " + machineId + " not found"));

        String url = UriComponentsBuilder.fromUriString(frontendOrigin)
                .path("/room/{roomId}")
                .queryParam("machine", machine.getId())
                .buildAndExpand(machine.getRoomId())
                .toUriString();

        return new MachineQrTargetResponse(machine.getId(), machine.getRoomId(), url);
    }
}
