package dev.washon.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.TestcontainersConfiguration;
import dev.washon.auth.application.SessionTokenService;
import dev.washon.auth.domain.AdminRole;
import dev.washon.auth.domain.AdminUser;
import dev.washon.auth.infrastructure.AdminUserRepository;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * GET /admin/machines/stream (live admin dashboard) over a real socket, like
 * RoomStreamApiTest: MockMvc cannot consume an open SSE stream. The session
 * is sent as the httpOnly cookie, which is what the browser's
 * EventSource(withCredentials) actually sends.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AdminStreamApiTest {

    private static final String PASSWORD = "admin-stream-test-password";
    private static final AtomicInteger SUFFIX = new AtomicInteger();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;
    @Autowired CameraRepository cameras;
    @Autowired AdminUserRepository adminUsers;

    private String sessionCookie;
    private long clock = Instant.now().toEpochMilli() - 60_000;

    @BeforeEach
    void logIn() throws Exception {
        AdminUser admin = new AdminUser();
        admin.setEmail("admin-stream-" + unique() + "@washon.test");
        admin.setPasswordHash(ScryptUtil.hashSecret(PASSWORD));
        admin.setRole(AdminRole.site_admin);
        adminUsers.saveAndFlush(admin);

        HttpResponse<String> login = send("POST", "/admin/auth/login", null,
                objectMapper.writeValueAsString(Map.of("email", admin.getEmail(), "password", PASSWORD)));
        assertThat(login.statusCode()).isEqualTo(200);
        String token = objectMapper.readTree(login.body()).get("token").asText();
        sessionCookie = SessionTokenService.COOKIE_NAME + "=" + token;
    }

    @Test
    void returns401WithoutASession() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/admin/machines/stream"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void signalsOcrDrivenChangesForMachinesInEveryRoom() throws Exception {
        TestCamera first = seedCamera("room-a");
        TestCamera second = seedCamera("room-b");

        try (SseTestClient client = new SseTestClient("/admin/machines/stream", sessionCookie)) {
            // Consensus needs 3 consecutive decreasing frames before in_use is committed.
            postFrames(first, "28", "25", "22");
            JsonNode signalA = client.nextFor(first.machineId(), 3000);
            assertThat(signalA.get("version").asInt()).isPositive();

            postFrames(second, "18", "15", "12");
            JsonNode signalB = client.nextFor(second.machineId(), 3000);
            assertThat(signalB.get("version").asInt()).isPositive();
        }
    }

    @Test
    void signalsAdminOverrides() throws Exception {
        TestCamera camera = seedCamera("override");

        try (SseTestClient client = new SseTestClient("/admin/machines/stream", sessionCookie)) {
            HttpResponse<String> override = send("POST", "/admin/machines/" + camera.machineId() + "/override",
                    sessionCookie, "{\"state\":\"maintenance\"}");
            assertThat(override.statusCode()).isEqualTo(200);

            JsonNode signal = client.nextFor(camera.machineId(), 3000);
            assertThat(signal.get("version").asInt()).isPositive();
        }
    }

    private void postFrames(TestCamera camera, String... rawTexts) throws Exception {
        for (String rawText : rawTexts) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("device_id", camera.deviceId());
            payload.put("captured_at", Instant.ofEpochMilli(clock += 1000).toString());
            payload.put("confidence", 0.95);
            payload.put("raw_text", rawText);
            payload.put("frame_hash", "frame-" + UUID.randomUUID());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/ingest/ocr"))
                    .header("X-Api-Key", camera.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
    }

    private HttpResponse<String> send(String method, String path, String cookie, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** One machine + camera in its own tenant/site/room, so every test touches a different room. */
    private TestCamera seedCamera(String label) {
        String suffix = label + "-" + unique();

        Tenant tenant = new Tenant();
        tenant.setName("admin-stream-tenant-" + suffix);
        tenants.saveAndFlush(tenant);

        Site site = new Site();
        site.setTenantId(tenant.getId());
        site.setName("admin-stream-site-" + suffix);
        sites.saveAndFlush(site);

        Room room = new Room();
        room.setSiteId(site.getId());
        room.setName("admin-stream-room-" + suffix);
        rooms.saveAndFlush(room);

        Machine machine = new Machine();
        machine.setRoomId(room.getId());
        machine.setType(MachineType.washer);
        machine.setLabel("admin-stream-" + suffix);
        machines.saveAndFlush(machine);

        String apiKey = "test_key_" + suffix;
        String deviceId = "admin-stream-device-" + suffix;
        Camera camera = new Camera();
        camera.setMachineId(machine.getId());
        camera.setDeviceId(deviceId);
        camera.setApiKeyHash(ScryptUtil.hashSecret(apiKey));
        camera.setStatus("online");
        cameras.saveAndFlush(camera);

        return new TestCamera(deviceId, apiKey, machine.getId());
    }

    private static String unique() {
        return System.nanoTime() + "-" + SUFFIX.incrementAndGet();
    }

    private record TestCamera(String deviceId, String apiKey, String machineId) {
    }

    private record SseEvent(String event, JsonNode data) {
    }

    /** Same minimal SSE reader as RoomStreamApiTest, plus a Cookie header. */
    private final class SseTestClient implements AutoCloseable {

        private final BlockingQueue<SseEvent> queue = new LinkedBlockingQueue<>();
        private final Thread reader;
        private volatile Throwable failure;
        private volatile InputStream body;

        SseTestClient(String path, String cookie) throws InterruptedException {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .header("Accept", "text/event-stream")
                    .header("Cookie", cookie)
                    .GET()
                    .build();
            this.reader = new Thread(() -> readLoop(request), "admin-sse-test-client-reader");
            this.reader.setDaemon(true);
            this.reader.start();
            // The server's listener is registered before it writes "ready", so once
            // "ready" arrives any commit the test triggers will be signalled.
            SseEvent ready = queue.poll(3000, TimeUnit.MILLISECONDS);
            assertThat(failure).as("SSE connect failure").isNull();
            assertThat(ready).as("ready event").isNotNull();
            assertThat(ready.event()).isEqualTo("ready");
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
                failure = e;
            }
        }

        /** Next machine_changed signal for {@code machineId}, skipping signals for other machines. */
        JsonNode nextFor(String machineId, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                SseEvent event = remaining > 0 ? queue.poll(remaining, TimeUnit.MILLISECONDS) : null;
                if (event == null) {
                    if (failure != null) {
                        throw new AssertionError("SSE stream failed while waiting for an event", failure);
                    }
                    throw new AssertionError("timed out after " + timeoutMs + "ms waiting for a signal for " + machineId);
                }
                assertThat(event.event()).isEqualTo("machine_changed");
                if (machineId.equals(event.data().get("machineId").asText())) {
                    return event.data();
                }
            }
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
