package dev.washon.machine.api;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end MQTT ingest against a real mosquitto broker (same image as
 * the dev server): a camera publishes to {prefix}/{device_id}/ocr and
 * the frame must land in machine_state_events through the same
 * OcrIngestService path as POST /ingest/ocr.
 *
 * <p>Rejections leave no trace but a log line, so each negative case
 * publishes the bad frame and then a good one from the same camera: the
 * listener handles one subscription's messages in order, so once the good
 * frame is persisted the bad one has definitely been processed (and must
 * not have been).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers
class OcrMqttListenerTest {

    @Container
    static final GenericContainer<?> MOSQUITTO = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2.0.20"))
            .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous true\n"),
                    "/mosquitto/config/mosquitto.conf")
            .withExposedPorts(1883);

    @DynamicPropertySource
    static void mqttProperties(DynamicPropertyRegistry registry) {
        registry.add("washon.mqtt.enabled", () -> "true");
        registry.add("washon.mqtt.url", () -> brokerUrl());
        registry.add("washon.mqtt.client-id", () -> "washon-backend-test");
        registry.add("washon.mqtt.topic-prefix", () -> PREFIX);
    }

    private static final String PREFIX = "test/washon";
    private static final AtomicInteger SUFFIX = new AtomicInteger();
    private static final long PERSIST_TIMEOUT_MS = 10_000;

    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;
    @Autowired CameraRepository cameras;
    @Autowired JdbcTemplate jdbc;

    private MqttClient camera;

    @BeforeEach
    void connectCamera() throws Exception {
        camera = new MqttClient(brokerUrl(), "test-camera-" + unique(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        camera.connect(options);
    }

    @AfterEach
    void disconnectCamera() throws Exception {
        camera.disconnect();
        camera.close();
    }

    @Test
    void persistsAValidFrameLikePostIngestOcr() throws Exception {
        TestCamera cam = seedCamera("mqtt-happy");

        publish(cam.deviceId(), frame("hash-ok"));

        assertThat(awaitEventCount(cam.machineId(), "hash-ok")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT source FROM machine_state_events WHERE machine_id = ? AND frame_hash = ?",
                String.class, cam.machineId(), "hash-ok")).isEqualTo("ocr");
    }

    @Test
    void dropsAFrameFromADeviceIdThatIsNotARegisteredCamera() throws Exception {
        TestCamera cam = seedCamera("mqtt-unknown");

        publish("wm-not-registered-" + unique(), frame("hash-unknown"));
        publish(cam.deviceId(), frame("hash-after-unknown"));

        assertThat(awaitEventCount(cam.machineId(), "hash-after-unknown")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM machine_state_events WHERE frame_hash = ?", Integer.class, "hash-unknown"))
                .isZero();
    }

    @Test
    void ignoresTopicsOutsideTheConfiguredPrefix() throws Exception {
        TestCamera cam = seedCamera("mqtt-prefix");

        MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(frame("hash-other-prefix")));
        message.setQos(1);
        camera.publish("prod/washon/" + cam.deviceId() + "/ocr", message);
        publish(cam.deviceId(), frame("hash-after-other-prefix"));

        assertThat(awaitEventCount(cam.machineId(), "hash-after-other-prefix")).isEqualTo(1);
        assertThat(eventCount(cam.machineId(), "hash-other-prefix")).isZero();
    }

    @Test
    void dropsAFrameWhosePayloadDeviceIdDiffersFromTheTopic() throws Exception {
        TestCamera cam = seedCamera("mqtt-mismatch");
        TestCamera other = seedCamera("mqtt-other");

        // Claims to be `other` in the payload, but published on cam's topic.
        Map<String, Object> spoofed = frame("hash-mismatch");
        spoofed.put("device_id", other.deviceId());
        publish(cam.deviceId(), spoofed);
        publish(cam.deviceId(), frame("hash-after-mismatch"));

        assertThat(awaitEventCount(cam.machineId(), "hash-after-mismatch")).isEqualTo(1);
        assertThat(eventCount(cam.machineId(), "hash-mismatch")).isZero();
        assertThat(eventCount(other.machineId(), "hash-mismatch")).isZero();
    }

    @Test
    void survivesMalformedJsonAndKeepsProcessing() throws Exception {
        TestCamera cam = seedCamera("mqtt-garbage");

        MqttMessage garbage = new MqttMessage("not json".getBytes(StandardCharsets.UTF_8));
        garbage.setQos(1);
        camera.publish(PREFIX + "/" + cam.deviceId() + "/ocr", garbage);
        publish(cam.deviceId(), frame("hash-after-garbage"));

        assertThat(awaitEventCount(cam.machineId(), "hash-after-garbage")).isEqualTo(1);
    }

    @Test
    void isIdempotentForARepeatedFrameHash() throws Exception {
        TestCamera cam = seedCamera("mqtt-dup");

        publish(cam.deviceId(), frame("hash-dup"));
        publish(cam.deviceId(), frame("hash-dup"));
        publish(cam.deviceId(), frame("hash-after-dup"));

        assertThat(awaitEventCount(cam.machineId(), "hash-after-dup")).isEqualTo(1);
        assertThat(eventCount(cam.machineId(), "hash-dup")).isEqualTo(1);
    }

    private void publish(String deviceId, Map<String, Object> payload) throws Exception {
        MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
        message.setQos(1);
        camera.publish(PREFIX + "/" + deviceId + "/ocr", message);
    }

    private int awaitEventCount(String machineId, String frameHash) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PERSIST_TIMEOUT_MS;
        int count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = eventCount(machineId, frameHash);
            if (count > 0) {
                return count;
            }
            Thread.sleep(100);
        }
        return count;
    }

    private int eventCount(String machineId, String frameHash) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM machine_state_events WHERE machine_id = ? AND frame_hash = ? AND source = 'ocr'",
                Integer.class, machineId, frameHash);
    }

    private static Map<String, Object> frame(String frameHash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("captured_at", Instant.now().toString());
        payload.put("confidence", 0.92);
        payload.put("raw_text", "23");
        payload.put("frame_hash", frameHash);
        return payload;
    }

    private TestCamera seedCamera(String label) {
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

        Machine machine = new Machine();
        machine.setRoomId(room.getId());
        machine.setType(MachineType.washer);
        machine.setLabel("e2e-" + suffix);
        machines.saveAndFlush(machine);

        String apiKey = "test_key_" + suffix;
        String deviceId = "e2e-device-" + suffix;
        Camera camera = new Camera();
        camera.setMachineId(machine.getId());
        camera.setDeviceId(deviceId);
        camera.setApiKeyHash(ScryptUtil.hashSecret(apiKey));
        camera.setStatus("online");
        cameras.saveAndFlush(camera);

        return new TestCamera(deviceId, apiKey, machine.getId());
    }

    private static String brokerUrl() {
        return "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883);
    }

    private static String unique() {
        return System.nanoTime() + "-" + SUFFIX.incrementAndGet();
    }

    private record TestCamera(String deviceId, String apiKey, String machineId) {
    }
}
