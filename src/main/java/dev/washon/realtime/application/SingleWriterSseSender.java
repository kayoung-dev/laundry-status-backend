package dev.washon.realtime.application;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
 */
final class SingleWriterSseSender {

    private static final Logger log = LoggerFactory.getLogger(SingleWriterSseSender.class);
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
    private static final QueueItem POISON = new QueueItem(null, null);

    private final SseEmitter emitter;
    private final BlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>();
    private final Thread writer;
    private volatile boolean closed = false;

    private record QueueItem(String eventName, Object data) {
    }

    SingleWriterSseSender(SseEmitter emitter) {
        this.emitter = emitter;
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
                QueueItem item = queue.take();
                if (item == POISON) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().name(item.eventName()).data(item.data()));
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
