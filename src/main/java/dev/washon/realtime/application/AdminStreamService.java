package dev.washon.realtime.application;

import dev.washon.realtime.payload.response.MachineChangeSignalResponse;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE gateway for the admin dashboard: one connection receives a
 * {@code machine_changed} signal for a commit to any machine in any room.
 * Unlike {@link RoomStreamService} there is no snapshot event -- the
 * dashboard fetches {@code GET /admin/machines} once the stream is open
 * (and on every reconnect). A {@code ready} event is sent first because the
 * response headers (and so the browser's EventSource "open") are only
 * flushed with the first write. The listener is registered before that, so
 * a commit landing between "open" and the fetch is still signalled.
 */
@Service
public class AdminStreamService {

    /** Paired with spring.mvc.async.request-timeout=-1: the connection stays open until the client disconnects. */
    private static final long NO_TIMEOUT = 0L;

    private final RoomEventBroker broker;

    public AdminStreamService(RoomEventBroker broker) {
        this.broker = broker;
    }

    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        SingleWriterSseSender sender = new SingleWriterSseSender(emitter);

        Runnable unsubscribe = broker.subscribeAll(event -> sender.enqueue("machine_changed",
                new MachineChangeSignalResponse(event.machineId(), event.version())));
        sender.enqueue("ready", Map.of());

        emitter.onCompletion(() -> {
            unsubscribe.run();
            sender.close();
        });
        emitter.onTimeout(() -> {
            unsubscribe.run();
            sender.close();
            emitter.complete();
        });
        emitter.onError(ex -> {
            unsubscribe.run();
            sender.close();
        });

        return emitter;
    }
}
