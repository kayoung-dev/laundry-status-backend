package dev.washon.machine.api;

import dev.washon.machine.application.MachineQrTargetService;
import dev.washon.machine.payload.response.MachineQrTargetResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2-001: per-machine QR code generation and routing. No auth -- a QR
 * sticker is scanned by students, matching the existing no-login room/report
 * flows (dev.washon.realtime.api.RoomStreamApi, dev.washon.report.api.ReportApi).
 */
@RestController
@RequestMapping("/machines")
public class MachineQrTargetApi {

    private final MachineQrTargetService machineQrTargetService;

    public MachineQrTargetApi(MachineQrTargetService machineQrTargetService) {
        this.machineQrTargetService = machineQrTargetService;
    }

    @GetMapping("/{machineId}/qr-target")
    public MachineQrTargetResponse getQrTarget(@PathVariable String machineId) {
        return machineQrTargetService.getQrTarget(machineId);
    }
}
