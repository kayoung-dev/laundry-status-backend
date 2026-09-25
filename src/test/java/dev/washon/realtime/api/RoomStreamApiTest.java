package dev.washon.realtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.TestcontainersConfiguration;
import dev.washon.common.crypto.ScryptUtil;
import dev.washon.facility.domain.Room;
import dev.washon.facility.domain.Site;
import dev.washon.facility.domain.Tenant;
import dev.washon.facility.infrastructure.RoomRepository;
import dev.washon.facility.infrastructure.SiteRepository;
import dev.washon.facility.infrastructure.TenantRepository;
import dev.washon.machine.domain.Camera;
import dev.washon.machine.domain.Machine;
import dev.washon.machine.domain.MachineType;
import dev.washon.machine.infrastructure.CameraRepository;
import dev.washon.machine.infrastructure.MachineRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Rewrites every scenario in apps/api/test/realtime-stream.e2e-spec.ts
 * against dev.washon.realtime.api.RoomStreamApi, using real MariaDB + Redis
 * (Testcontainers) and a live socket (via java.net.http.HttpClient in
 * streaming mode) instead of MockMvc, which cannot consume an open SSE
 * stream. Mirrors the original Jest test's hand-rolled raw-socket SSE parser.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RoomStreamApiTest {

    private static final Logger log = LoggerFactory.getLogger(RoomStreamApiTest.class);
    private static final AtomicInteger SUFFIX = new AtomicInteger();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;
    @Autowired CameraRepository cameras;

    /** Base capture time; frames get strictly increasing captured_at from it, like the original test. */
    private long clock = Instant.now().toEpochMilli() - 60_000;

    @Test
    void ac3SendsASnapshotAsTheFirstEventWithCurrentStateOfEveryMachineInTheRoom() throws Exception {
        RoomFixture room = seedRoom("snapshot", 2);

        try (SseTestClient client = new SseTestClient(port, "/rooms/" + room.roomId() + "/stream")) {
            SseEvent first = client.next(3000);
            assertThat(first.event()).isEqualTo("snapshot");
            assertThat(first.data().get("roomId").asText()).isEqualTo(room.roomId());

            JsonNode machinesNode = first.data().get("machines");
            assertThat(machinesNode).hasSize(2);
            for (TestCamera camera : room.cameras()) {
                JsonNode entry = findByMachineId(machinesNode, camera.machineId());
                assertThat(entry).as("snapshot entry for machine " + camera.machineId()).isNotNull();
                // No OCR frame has landed yet, so there is no MachineCurrentState row.
                assertThat(entry.get("state").asText()).isEqualTo("unknown");
                assertThat(entry.get("remainingMin").isNull()).isTrue();
            }
        }
    }

    @Test
    void ac1Ac2DeliversAMachineChangedEventWithinTwoSecondsOfTheTriggeringOcrIngestion() throws Exception {
        RoomFixture room = seedRoom("latency", 1);
        TestCamera camera = room.cameras().get(0);

        try (SseTestClient client = new SseTestClient(port, "/rooms/" + room.roomId() + "/stream")) {
            SseEvent snapshot = client.next(3000);
            assertThat(snapshot.event()).isEqualTo("snapshot");

            // Consensus needs 3 consecutive decreasing frames; the first two do not
            // change state, so they sit outside the timed window.
            postFrame(camera, "28");
            postFrame(camera, "25");

            long ingestStart = System.currentTimeMillis();
            postFrame(camera, "22"); // crosses the consensus threshold -> in_use

            SseEvent changed = client.next(2000);
            long latencyMs = System.currentTimeMillis() - ingestStart;

            assertThat(changed.event()).isEqualTo("machine_changed");
            assertThat(changed.data().get("machineId").asText()).isEqualTo(camera.machineId());
            assertThat(changed.data().get("state").asText()).isEqualTo("in_use");
            assertThat(changed.data().get("remainingMin").asInt()).isEqualTo(22);
            assertThat(latencyMs).isLessThanOrEqualTo(2000);

            log.info("[SB-003] measured ingest-to-SSE latency: {}ms", latencyMs);
        }
    }

    @Test
    void deliversOnlyEventsForMachinesInTheSubscribedRoomFilteringOutOtherRooms() throws Exception {
        RoomFixture watched = seedRoom("filter-watched", 1);
        RoomFixture other = seedRoom("filter-other", 1);

        try (SseTestClient client = new SseTestClient(port, "/rooms/" + watched.roomId() + "/stream")) {
            client.next(3000); // snapshot

            // Drive the OTHER room's machine through a full transition first.
            postFrame(other.cameras().get(0), "28");
            postFrame(other.cameras().get(0), "25");
            postFrame(other.cameras().get(0), "22");

            // Then the watched room's machine, which must be the one and only
            // machine_changed event this client receives.
            postFrame(watched.cameras().get(0), "18");
            postFrame(watched.cameras().get(0), "15");
            postFrame(watched.cameras().get(0), "12");

            SseEvent changed = client.next(2000);
            assertThat(changed.event()).isEqualTo("machine_changed");
            assertThat(changed.data().get("machineId").asText()).isEqualTo(watched.cameras().get(0).machineId());
        }
    }

    @Test
    void returns404ForARoomThatDoesNotExist() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/rooms/no-such-room-" + unique() + "/stream"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    private static JsonNode findByMachineId(JsonNode machinesNode, String machineId) {
        for (JsonNode entry : machinesNode) {
            if (machineId.equals(entry.get("machineId").asText())) {
                return entry;
            }
        }
        return null;
    }

    private void postFrame(TestCamera camera, String rawText) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", camera.deviceId());
        payload.put("captured_at", nextCapturedAt().toString());
        payload.put("confidence", 0.95);
        payload.put("raw_text", rawText);
        payload.put("frame_hash", "frame-" + UUID.randomUUID());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/ingest/ocr"))
                .header("X-Api-Key", camera.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private Instant nextCapturedAt() {
        clock += 1000;
        return Instant.ofEpochMilli(clock);
    }

    /** Seeds a room with `count` machines (each with its own camera), like the original test's seedRoom helper. */
    private RoomFixture seedRoom(String label, int count) {
        String suffix = label + "-" + unique();

        Tenant tenant = new Tenant();
        tenant.setName("e2e-tenant-" + suffix);
        tenants.saveAndFlush(tenant);

        Site site = new Site();
        site.setTenantId(tenant.getId());
        site.setName("e2e-site-" + suffix);
        sites.saveAndFlush(site);

        Room room = new Room();
        room.setSiteId(site.getId());
        room.setName("e2e-room-" + suffix);
        rooms.saveAndFlush(room);

        List<TestCamera> testCameras = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Machine machine = new Machine();
            machine.setRoomId(room.getId());
            machine.setType(MachineType.washer);
            machine.setLabel("e2e-" + label + "-" + i + "-" + suffix);
            machines.saveAndFlush(machine);

            String apiKey = "test_key_" + suffix + "_" + i;
            String deviceId = "e2e-device-" + label + "-" + i + "-" + suffix;
            Camera camera = new Camera();
            camera.setMachineId(machine.getId());
            camera.setDeviceId(deviceId);
            camera.setApiKeyHash(ScryptUtil.hashSecret(apiKey));
            camera.setStatus("online");
            cameras.saveAndFlush(camera);

            testCameras.add(new TestCamera(deviceId, apiKey, machine.getId()));
        }
        return new RoomFixture(room.getId(), testCameras);
    }

    private static String unique() {
        return System.nanoTime() + "-" + SUFFIX.incrementAndGet();
    }

    private record RoomFixture(String roomId, List<TestCamera> cameras) {
    }

    private record TestCamera(String deviceId, String apiKey, String machineId) {
    }

    private record SseEvent(String event, JsonNode data) {
    }

    /**
     * Minimal SSE reader mirroring the original Jest test's raw-socket parser:
     * java.net.http.HttpClient's ofInputStream() body handler returns as soon
     * as headers arrive, handing back a live stream instead of buffering the
     * whole (never-ending) response body.
     */
    private final class SseTestClient implements AutoCloseable {

        private final BlockingQueue<SseEvent> queue = new LinkedBlockingQueue<>();
        private final Thread reader;
        private volatile Throwable failure;
        private volatile InputStream body;

        SseTestClient(int port, String path) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();
            this.reader = new Thread(() -> readLoop(request), "sse-test-client-reader");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        private void readLoop(HttpRequest request) {
            try {
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 400) {
                    failure = new IllegalStateException("SSE request failed with status " + response.statusCode());
                    return;
                }
                body = response.body();
                BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                String eventName = "message";
                StringBuilder dataLines = new StringBuilder();
                boolean sawData = false;
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (sawData) {
                            queue.add(new SseEvent(eventName, objectMapper.readTree(dataLines.toString())));
                        }
                        eventName = "message";
                        dataLines.setLength(0);
                        sawData = false;
                    } else if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (sawData) {
                            dataLines.append('\n');
                        }
                        dataLines.append(line.substring("data:".length()).trim());
                        sawData = true;
                    }
                }
            } catch (IOException | InterruptedException e) {
                // Expected once close() closes the socket mid-read; only surface it
                // as a failure if a waiter is still expecting an event afterwards.
                failure = e;
            }
        }

        SseEvent next(long timeoutMs) throws InterruptedException {
            SseEvent event = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (event != null) {
                return event;
            }
            if (failure != null) {
                throw new AssertionError("SSE stream failed while waiting for an event", failure);
            }
            throw new AssertionError("timed out after " + timeoutMs + "ms waiting for an SSE event");
        }

        @Override
        public void close() {
            try {
                if (body != null) {
                    body.close();
                }
            } catch (IOException ignored) {
                // closing an already-broken stream
            }
            reader.interrupt();
        }
    }
}
