package dev.washon.realtime.application;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link SseEmitter#send} is not thread-safe, but a room's OCR-driven commits
 * can land concurrently from different machines/threads. Every enqueued event
 * for one connection goes through this single-writer queue, drained by one
 * dedicated background thread, so writes to that connection's emitter never
 * interleave.
 *
 * <p>The same writer thread also sends a {@code :ping} SSE comment whenever
 * the connection has been quiet for {@link #HEARTBEAT_INTERVAL}, so reverse
 * proxies and load balancers in front of the server do not drop an idle
 * stream (a room where no machine changes state). Comment lines never reach
 * the browser's {@code EventSource} handlers. A failed ping also completes
 * the emitter, cleaning up connections whose client has silently gone away.
 */
final class SingleWriterSseSender {

    private static final Logger log = LoggerFactory.getLogger(SingleWriterSseSender.class);
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
    private static final QueueItem POISON = new QueueItem(null, null);

    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final SseEmitter emitter;
    private final BlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>();
    private final long heartbeatMillis;
    private final Thread writer;
    private volatile boolean closed = false;

    private record QueueItem(String eventName, Object data) {
    }

    SingleWriterSseSender(SseEmitter emitter) {
        this(emitter, HEARTBEAT_INTERVAL);
    }

    /** Package-private so tests can use a short heartbeat instead of waiting 15 seconds. */
    SingleWriterSseSender(SseEmitter emitter, Duration heartbeatInterval) {
        this.emitter = emitter;
        this.heartbeatMillis = heartbeatInterval.toMillis();
        this.writer = new Thread(this::drain, "sse-writer-" + THREAD_SEQ.incrementAndGet());
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /** Non-blocking; safe to call from any thread (e.g. a @TransactionalEventListener callback). */
    void enqueue(String eventName, Object data) {
        if (closed) {
            return;
        }
        queue.add(new QueueItem(eventName, data));
    }

    private void drain() {
        try {
            while (true) {
                QueueItem item = queue.poll(heartbeatMillis, TimeUnit.MILLISECONDS);
                if (item == POISON) {
                    return;
                }
                try {
                    if (item == null) {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } else {
                        emitter.send(SseEmitter.event().name(item.eventName()).data(item.data()));
                    }
                } catch (IOException | IllegalStateException e) {
                    log.debug("SSE send failed, completing emitter with error", e);
                    emitter.completeWithError(e);
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops the writer thread; safe to call more than once. */
    void close() {
        closed = true;
        queue.add(POISON);
    }
}
