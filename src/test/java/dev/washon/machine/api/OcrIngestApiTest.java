package dev.washon.machine.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Rewrites every scenario in apps/api/test/ingest-ocr.e2e-spec.ts against
 * dev.washon.machine.api.OcrIngestApi, using real MariaDB + Redis
 * (Testcontainers) instead of mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OcrIngestApiTest {

    private static final AtomicInteger SUFFIX = new AtomicInteger();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;
    @Autowired CameraRepository cameras;
    @Autowired JdbcTemplate jdbc;

    @Test
    void acceptsAValidPayloadAndPersistsExactlyOneEvent() throws Exception {
        TestCamera camera = seedCamera("happy-path");
        Map<String, Object> payload = ocrPayload(camera.deviceId(), "frame-" + unique());

        mockMvc.perform(post("/ingest/ocr")
                        .header("X-Api-Key", camera.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.eventId", not(emptyString())));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM machine_state_events WHERE machine_id = ? AND frame_hash = ? AND source = 'ocr'",
                Integer.class, camera.machineId(), payload.get("frame_hash"));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void isIdempotentForARepeatedFrameHashNoDuplicateRowStillSuccessful() throws Exception {
        TestCamera camera = seedCamera("idempotent");
        Map<String, Object> payload = ocrPayload(camera.deviceId(), "frame-" + unique());
        String body = objectMapper.writeValueAsString(payload);

        String firstResponse = mockMvc.perform(post("/ingest/ocr")
                        .header("X-Api-Key", camera.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        String firstEventId = objectMapper.readTree(firstResponse).get("eventId").asText();

        mockMvc.perform(post("/ingest/ocr")
                        .header("X-Api-Key", camera.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.eventId").value(firstEventId));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM machine_state_events WHERE machine_id = ? AND frame_hash = ?",
                Integer.class, camera.machineId(), payload.get("frame_hash"));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsARequestWithNoApiKeyHeader() throws Exception {
        TestCamera camera = seedCamera("auth-missing");
        Map<String, Object> payload = ocrPayload(camera.deviceId(), "frame-" + unique());

        mockMvc.perform(post("/ingest/ocr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsARequestWithAWrongApiKey() throws Exception {
        TestCamera camera = seedCamera("auth-wrong");
        Map<String, Object> payload = ocrPayload(camera.deviceId(), "frame-" + unique());

        mockMvc.perform(post("/ingest/ocr")
                        .header("X-Api-Key", "totally-wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsARequestForAnUnknownDeviceId() throws Exception {
        Map<String, Object> payload = ocrPayload("no-such-device-ever-" + unique(), "frame-" + unique());

        mockMvc.perform(post("/ingest/ocr")
                        .header("X-Api-Key", "any-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsABurstOfFiveRequestsThenRejectsTheSixthWith429() throws Exception {
        TestCamera camera = seedCamera("rate-limit");

        for (int i = 0; i < 5; i++) {
            Map<String, Object> payload = ocrPayload(camera.deviceId(), "burst-" + i + "-" + unique());
            mockMvc.perform(post("/ingest/ocr")
                            .header("X-Api-Key", camera.apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());
        }

        Map<String, Object> overflow = ocrPayload(camera.deviceId(), "burst-overflow-" + unique());
        mockMvc.perform(post("/ingest/ocr")
                        .header("X-Api-Key", camera.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overflow)))
                .andExpect(status().isTooManyRequests());
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

    private static Map<String, Object> ocrPayload(String deviceId, String frameHash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", deviceId);
        payload.put("captured_at", Instant.now().toString());
        payload.put("confidence", 0.92);
        payload.put("raw_text", "23");
        payload.put("frame_hash", frameHash);
        return payload;
    }

    private static String unique() {
        return System.nanoTime() + "-" + SUFFIX.incrementAndGet();
    }

    private record TestCamera(String deviceId, String apiKey, String machineId) {
    }
}
