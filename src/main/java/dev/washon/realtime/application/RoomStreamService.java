package dev.washon.realtime.application;

import dev.washon.machine.domain.Machine;
import dev.washon.machine.domain.MachineCurrentState;
import dev.washon.machine.domain.MachineState;
import dev.washon.machine.infrastructure.MachineCurrentStateRepository;
import dev.washon.machine.infrastructure.MachineRepository;
import dev.washon.realtime.domain.MachineChangedEvent;
import dev.washon.realtime.payload.response.MachineChangedResponse;
import dev.washon.realtime.payload.response.MachineSnapshotItem;
import dev.washon.realtime.payload.response.MachineSnapshotResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE gateway for one room (SB-003). Sends a {@code snapshot} event
 * immediately on connect (also covers client reconnects, which just open a
 * new connection), then a {@code machine_changed} event on every subsequent
 * commit to a machine in this room. Ported from
 * apps/api/src/realtime/realtime.service.ts.
 *
 * <p><b>Ordering guarantee</b>: the room-filtered listener is registered with
 * {@link RoomEventBroker} BEFORE the snapshot query runs. Any event that
 * lands while that query is in flight is buffered (under {@code orderLock})
 * rather than sent immediately, then flushed right after the snapshot is
 * enqueued -- so a state change committed mid-connect can never be lost or
 * delivered ahead of the snapshot it should follow.
 */
@Service
public class RoomStreamService {

    /** Paired with spring.mvc.async.request-timeout=-1: the connection stays open until the client disconnects. */
    private static final long NO_TIMEOUT = 0L;

    private final MachineRepository machines;
    private final MachineCurrentStateRepository currentStates;
    private final RoomEventBroker broker;

    public RoomStreamService(MachineRepository machines, MachineCurrentStateRepository currentStates,
            RoomEventBroker broker) {
        this.machines = machines;
        this.currentStates = currentStates;
        this.broker = broker;
    }

    public SseEmitter stream(String roomId) {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        SingleWriterSseSender sender = new SingleWriterSseSender(emitter);

        Object orderLock = new Object();
        List<MachineChangedEvent> buffered = new ArrayList<>();
        boolean[] snapshotSent = {false};

        // 1) Subscribe BEFORE the snapshot query, so a commit landing mid-query is buffered, not lost.
        Runnable[] unsubscribeHolder = new Runnable[1];
        unsubscribeHolder[0] = broker.subscribe(roomId, event -> {
            synchronized (orderLock) {
                if (!snapshotSent[0]) {
                    buffered.add(event);
                } else {
                    sender.enqueue("machine_changed", toChangedResponse(event));
                }
            }
        });

        emitter.onCompletion(() -> {
            unsubscribeHolder[0].run();
            sender.close();
        });
        emitter.onTimeout(() -> {
            unsubscribeHolder[0].run();
            sender.close();
            emitter.complete();
        });
        emitter.onError(ex -> {
            unsubscribeHolder[0].run();
            sender.close();
        });

        // 2) Run the snapshot query, then flush snapshot + anything buffered, in that order.
        List<MachineSnapshotItem> snapshot;
        try {
            snapshot = snapshot(roomId);
        } catch (RuntimeException e) {
            unsubscribeHolder[0].run();
            sender.close();
            emitter.completeWithError(e);
            return emitter;
        }

        synchronized (orderLock) {
            sender.enqueue("snapshot", new MachineSnapshotResponse(roomId, snapshot));
            for (MachineChangedEvent event : buffered) {
                sender.enqueue("machine_changed", toChangedResponse(event));
            }
            buffered.clear();
            snapshotSent[0] = true;
        }

        return emitter;
    }

    /** Current state of every machine in the room, ordered like the original Prisma query (createdAt asc). */
    public List<MachineSnapshotItem> snapshot(String roomId) {
        List<Machine> roomMachines = machines.findByRoomIdOrderByCreatedAtAsc(roomId);
        Map<String, MachineCurrentState> byMachineId = currentStates
                .findAllById(roomMachines.stream().map(Machine::getId).toList())
                .stream()
                .collect(Collectors.toMap(MachineCurrentState::getMachineId, s -> s));

        List<MachineSnapshotItem> items = new ArrayList<>(roomMachines.size());
        for (Machine machine : roomMachines) {
            MachineCurrentState state = byMachineId.get(machine.getId());
            items.add(new MachineSnapshotItem(
                    machine.getId(),
                    machine.getType(),
                    machine.getLabel(),
                    state != null ? state.getState() : MachineState.unknown,
                    state != null ? state.getRemainingMin() : null,
                    state != null ? state.getErrorCode() : null,
                    state != null ? state.getWashStage() : null,
                    state != null && state.isOverrideActive(),
                    state != null ? state.getVersion() : 0,
                    state != null ? state.getUpdatedAt() : null));
        }
        return items;
    }

    private static MachineChangedResponse toChangedResponse(MachineChangedEvent event) {
        return new MachineChangedResponse(event.machineId(), event.state(), event.remainingMin(),
                event.errorCode(), event.washStage(), event.version(), event.updatedAt());
    }
}
