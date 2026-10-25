package dev.washon.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SingleWriterSseSenderTest {

    private static final Duration SHORT_HEARTBEAT = Duration.ofMillis(100);

    @Test
    void sendsAPingCommentWhenTheConnectionIsIdle() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        SingleWriterSseSender sender = new SingleWriterSseSender(emitter, SHORT_HEARTBEAT);
        try {
            assertThat(emitter.next()).isEqualTo(":ping\n\n");
            assertThat(emitter.next()).isEqualTo(":ping\n\n");
        } finally {
            sender.close();
        }
    }

    @Test
    void sendsEnqueuedEventsAsNamedEventsNotPings() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        SingleWriterSseSender sender = new SingleWriterSseSender(emitter, Duration.ofSeconds(30));
        try {
            sender.enqueue("machine_changed", "hello");
            assertThat(emitter.next()).isEqualTo("event:machine_changed\ndata:hello\n\n");
        } finally {
            sender.close();
        }
    }

    @Test
    void completesTheEmitterWhenAPingFails() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        emitter.failSends = true;
        SingleWriterSseSender sender = new SingleWriterSseSender(emitter, SHORT_HEARTBEAT);
        try {
            assertThat(emitter.completedWithError.poll(2, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
        } finally {
            sender.close();
        }
    }

    /** Records each sent event as its raw SSE wire text instead of writing to a response. */
    private static final class CapturingEmitter extends SseEmitter {

        private final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
        private final BlockingQueue<Throwable> completedWithError = new LinkedBlockingQueue<>();
        private volatile boolean failSends;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failSends) {
                throw new IOException("client went away");
            }
            List<String> parts = builder.build().stream()
                    .map(part -> String.valueOf(part.getData()))
                    .toList();
            sent.add(parts.stream().collect(Collectors.joining()));
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedWithError.add(ex);
        }

        String next() throws InterruptedException {
            String event = sent.poll(2, TimeUnit.SECONDS);
            assertThat(event).as("an SSE write within 2s").isNotNull();
            return event;
        }
    }
}
