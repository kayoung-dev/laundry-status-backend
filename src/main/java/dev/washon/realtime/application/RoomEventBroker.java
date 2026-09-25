package dev.washon.realtime.application;

import dev.washon.realtime.domain.MachineChangedEvent;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-process pub/sub between the state machine (publisher) and the SSE
 * gateway (subscriber, one listener per open connection). Ported from
 * apps/api/src/realtime/machine-events.emitter.ts.
 *
 * <p>{@link #onMachineChanged} only runs {@code AFTER_COMMIT}: publishing
 * before the triggering transaction commits would let a client observe a
 * change that a concurrent reader (or a crash) could still roll back.
 */
@Component
public class RoomEventBroker {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<MachineChangedEvent>>> listenersByRoom =
            new ConcurrentHashMap<>();

    /** Registers a room-filtered listener; returns the unsubscribe action. */
    public Runnable subscribe(String roomId, Consumer<MachineChangedEvent> listener) {
        CopyOnWriteArrayList<Consumer<MachineChangedEvent>> listeners =
                listenersByRoom.computeIfAbsent(roomId, id -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMachineChanged(MachineChangedEvent event) {
        CopyOnWriteArrayList<Consumer<MachineChangedEvent>> listeners = listenersByRoom.get(event.roomId());
        if (listeners == null) {
            return;
        }
        for (Consumer<MachineChangedEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
