package dev.washon.admin.application;

import dev.washon.admin.payload.response.AuditEntryResponse;
import dev.washon.admin.payload.response.AuditEntryResponse.OverrideAuditDetail;
import dev.washon.admin.payload.response.DashboardMachineResponse;
import dev.washon.admin.payload.response.OverrideResponse;
import dev.washon.auth.domain.AuthenticatedAdmin;
import dev.washon.facility.domain.Room;
import dev.washon.facility.infrastructure.RoomRepository;
import dev.washon.machine.application.MachineStateService;
import dev.washon.machine.application.StateMachineProperties;
import dev.washon.machine.domain.EventSource;
import dev.washon.machine.domain.Machine;
import dev.washon.machine.domain.MachineCurrentState;
import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.MachineStateEvent;
import dev.washon.machine.infrastructure.MachineCurrentStateRepository;
import dev.washon.machine.infrastructure.MachineRepository;
import dev.washon.machine.infrastructure.MachineStateEventRepository;
import dev.washon.report.application.ReportService;
import dev.washon.report.payload.response.NeedsCheckResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin control plane: dashboard, manual override and its audit log. Owns no
 * entities -- reads the machine/report domains, and writes overrides only
 * through {@link MachineStateService} (the machine's single-writer lock
 * path). Ported from apps/api/src/admin/admin.service.ts.
 */
@Service
public class AdminService {

    /**
     * An override pins a machine to a human-decided state the camera cannot
     * infer. {@code in_use}/{@code done}/{@code available} are deliberately
     * excluded: those are the state machine's job, and pinning them would
     * freeze a machine on a value nobody would remember to clear.
     */
    static final List<MachineState> OVERRIDABLE_STATES =
            List.of(MachineState.maintenance, MachineState.error, MachineState.unknown);

    static final int DEFAULT_AUDIT_LIMIT = 50;
    static final int MAX_AUDIT_LIMIT = 200;

    private final MachineRepository machines;
    private final MachineCurrentStateRepository currentStates;
    private final MachineStateEventRepository events;
    private final RoomRepository rooms;
    private final ReportService reports;
    private final MachineStateService machineStateService;
    private final OverrideAuditCodec auditCodec;
    private final StateMachineProperties stateMachineProperties;

    public AdminService(MachineRepository machines, MachineCurrentStateRepository currentStates,
            MachineStateEventRepository events, RoomRepository rooms, ReportService reports,
            MachineStateService machineStateService, OverrideAuditCodec auditCodec,
            StateMachineProperties stateMachineProperties) {
        this.machines = machines;
        this.currentStates = currentStates;
        this.events = events;
        this.rooms = rooms;
        this.reports = reports;
        this.machineStateService = machineStateService;
        this.auditCodec = auditCodec;
        this.stateMachineProperties = stateMachineProperties;
    }

    /**
     * One row per machine: current state, remaining time, error code and the
     * needs-check flag. Needs-check reuses {@link ReportService#getNeedsCheck}
     * per machine rather than a second copy of the rule -- one COUNT DISTINCT
     * per machine is fine at single-facility scale.
     */
    public List<DashboardMachineResponse> getDashboard() {
        List<Machine> all = machines.findAllByOrderByRoomIdAscCreatedAtAsc();
        List<String> machineIds = all.stream().map(Machine::getId).toList();
        Map<String, MachineCurrentState> stateById = currentStates.findAllById(machineIds).stream()
                .collect(Collectors.toMap(MachineCurrentState::getMachineId, Function.identity()));
        Map<String, String> roomNameById = rooms.findAllById(all.stream().map(Machine::getRoomId).distinct().toList())
                .stream().collect(Collectors.toMap(Room::getId, Room::getName));
        Instant now = Instant.now();

        return all.stream().map(machine -> {
            MachineCurrentState current = stateById.get(machine.getId());
            NeedsCheckResponse needsCheck = reports.getNeedsCheck(machine.getId());
            Instant lastFrameAt = current != null ? current.getLastFrameAt() : null;
            // Same StateMachineProperties.isCameraDown used by CameraDownMonitor's
            // sweep (SB-007) -- the dashboard badge and the sweep's log line can
            // never disagree about what "camera down" means.
            boolean cameraDown = stateMachineProperties.isCameraDown(lastFrameAt, now);
            return new DashboardMachineResponse(
                    machine.getId(),
                    machine.getLabel(),
                    machine.getType(),
                    machine.getRoomId(),
                    roomNameById.get(machine.getRoomId()),
                    current != null ? current.getState() : MachineState.unknown,
                    current != null ? current.getRemainingMin() : null,
                    current != null ? current.getErrorCode() : null,
                    current != null && current.isOverrideActive(),
                    current != null ? current.getOverrideBy() : null,
                    needsCheck.needsCheck(),
                    needsCheck.distinctDeviceCount(),
                    current != null ? current.getVersion() : 0,
                    current != null ? current.getUpdatedAt() : null,
                    lastFrameAt,
                    cameraDown,
                    cameraDown ? lastFrameAt : null);
        }).toList();
    }

    public OverrideResponse setOverride(String machineId, String state, AuthenticatedAdmin admin) {
        MachineState target = parseOverridableState(state);
        requireMachine(machineId);
        String detail = auditCodec.encode(new OverrideAuditDetail(OverrideAuditCodec.SET, admin.id(), admin.email()));
        return OverrideResponse.from(machineStateService.setOverride(machineId, target, admin.email(), detail));
    }

    public OverrideResponse clearOverride(String machineId, AuthenticatedAdmin admin) {
        requireMachine(machineId);
        String detail = auditCodec.encode(
                new OverrideAuditDetail(OverrideAuditCodec.CLEAR, admin.id(), admin.email()));
        return OverrideResponse.from(machineStateService.clearOverride(machineId, detail));
    }

    /** Newest first; {@code source} (optional) narrows to one event source, e.g. {@code admin_override}. */
    public List<AuditEntryResponse> getAuditLog(String machineId, Integer limit, String source) {
        if (limit != null && limit < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be a positive integer");
        }
        EventSource sourceFilter = source != null ? parseSource(source) : null;
        requireMachine(machineId);

        Limit take = Limit.of(Math.min(limit != null ? limit : DEFAULT_AUDIT_LIMIT, MAX_AUDIT_LIMIT));
        List<MachineStateEvent> rows = sourceFilter != null
                ? events.findLatestByMachineIdAndSource(machineId, sourceFilter, take)
                : events.findLatestByMachineId(machineId, take);

        return rows.stream().map(event -> new AuditEntryResponse(event.getId(), event.getMachineId(),
                event.getSource(), event.getState(), event.getVersion(), event.getCreatedAt(),
                auditCodec.decode(event))).toList();
    }

    private static MachineState parseOverridableState(String state) {
        return OVERRIDABLE_STATES.stream().filter(s -> s.name().equals(state)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "state must be one of: " + OVERRIDABLE_STATES.stream().map(Enum::name)
                                .collect(Collectors.joining(", "))));
    }

    private static EventSource parseSource(String source) {
        return Arrays.stream(EventSource.values()).filter(s -> s.name().equals(source)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "source must be one of: " + Arrays.stream(EventSource.values()).map(Enum::name)
                                .collect(Collectors.joining(", "))));
    }

    private void requireMachine(String machineId) {
        if (!machines.existsById(machineId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine " + machineId + " not found");
        }
    }
}
