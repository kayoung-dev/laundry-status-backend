package dev.washon.machine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.TestcontainersConfiguration;
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
import dev.washon.machine.infrastructure.MachineCurrentStateRepository;
import dev.washon.machine.infrastructure.MachineRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rewrites apps/api/test/camera-down.e2e-spec.ts (US-009 / SB-007) against
 * {@link CameraDownMonitor} and the {@code cameraDown}/{@code cameraDownSince}
 * fields the dashboard (SB-006's {@code GET /admin/machines}) now exposes.
 *
 * <p>The 5-minute production threshold ({@code washon.state-machine.camera-down})
 * is left at its shipped default and never waited out in real time: every
 * scenario backdates {@code MachineCurrentState.lastFrameAt} directly through
 * the repository instead, exactly as the original Prisma-based test did. The
 * sweep interval is overridden to something far longer than any test run so
 * the background {@code @Scheduled} sweep cannot race the sweeps this test
 * triggers manually via {@link CameraDownMonitor#sweep()}.
 */
@SpringBootTest(properties = "washon.state-machine.camera-down-sweep=1h")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CameraDownMonitorTest {

    private static final AtomicInteger SUFFIX = new AtomicInteger();
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;
    @Autowired CameraRepository cameras;
    @Autowired MachineCurrentStateRepository currentStates;
    @Autowired AdminUserRepository adminUsers;
    @Autowired StateMachineProperties properties;
    @Autowired CameraDownMonitor monitor;

    private String token;

    @BeforeEach
    void logIn() throws Exception {
        AdminUser admin = new AdminUser();
        admin.setEmail("camera-down-" + unique() + "@washon.test");
        admin.setPasswordHash(ScryptUtil.hashSecret(PASSWORD));
        admin.setRole(AdminRole.site_admin);
        adminUsers.saveAndFlush(admin);

        MvcResult login = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", admin.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void showsCameraDownFalseForAMachineWithARecentFrame() throws Exception {
        TestCamera camera = seedCamera("recent");
        postFrame(camera, "05");

        JsonNode entry = dashboardEntry(camera.machineId());
        assertThat(entry.get("cameraDown").asBoolean()).isFalse();
        assertThat(entry.get("cameraDownSince").isNull()).isTrue();
    }

    @Test
    void treatsAMachineThatHasNeverSentAFrameAsNotCameraDown() throws Exception {
        TestCamera camera = seedCamera("never-seen");

        JsonNode entry = dashboardEntry(camera.machineId());
        assertThat(entry.get("cameraDown").asBoolean()).isFalse();
    }

    @Test
    void showsCameraDownTrueOnceLastFrameAtIsAtLeastFiveMinutesOldAndAutoClearsOnceAFreshFrameLands() throws Exception {
        TestCamera camera = seedCamera("down-and-recover");
        postFrame(camera, "05");
        ageLastFrame(camera.machineId(), properties.cameraDown().plusSeconds(5));

        JsonNode down = dashboardEntry(camera.machineId());
        assertThat(down.get("cameraDown").asBoolean()).isTrue();
        assertThat(down.get("cameraDownSince").isNull()).isFalse();

        postFrame(camera, "04");

        JsonNode recovered = dashboardEntry(camera.machineId());
        assertThat(recovered.get("cameraDown").asBoolean()).isFalse();
        assertThat(recovered.get("cameraDownSince").isNull()).isTrue();
    }

    @Test
    void doesNotFlagAMachineThatIsMerelyCloseToButUnderTheThreshold() throws Exception {
        TestCamera camera = seedCamera("almost-down");
        postFrame(camera, "05");
        ageLastFrame(camera.machineId(), properties.cameraDown().minusSeconds(5));

        JsonNode entry = dashboardEntry(camera.machineId());
        assertThat(entry.get("cameraDown").asBoolean()).isFalse();
    }

    @Test
    void logsAWarningOnceWhenASweepCrossesIntoCameraDownNotAgainOnLaterTicksWhileItStaysDown() throws Exception {
        TestCamera camera = seedCamera("logged");
        postFrame(camera, "05");
        ageLastFrame(camera.machineId(), properties.cameraDown().plusSeconds(5));

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            monitor.sweep();
            monitor.sweep();
            monitor.sweep();

            List<ILoggingEvent> downLogs = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(camera.machineId()))
                    .toList();
            assertThat(downLogs).hasSize(1);
        } finally {
            detachAppender(appender);
        }

        // A fresh frame clears the in-memory "already logged" mark so a future
        // camera-down episode for the same machine would be logged again.
        ListAppender<ILoggingEvent> recoveryAppender = attachAppender();
        try {
            postFrame(camera, "04");
            monitor.sweep();

            List<ILoggingEvent> recoveryLogs = recoveryAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains(camera.machineId()))
                    .toList();
            assertThat(recoveryLogs).hasSize(1);
        } finally {
            detachAppender(recoveryAppender);
        }
    }

    // ---------------------------------------------------------------- helpers

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(CameraDownMonitor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(CameraDownMonitor.class);
        logbackLogger.detachAppender(appender);
        appender.stop();
    }

    private void ageLastFrame(String machineId, Duration age) {
        var state = currentStates.findById(machineId).orElseThrow();
        state.setLastFrameAt(Instant.now().minus(age));
        currentStates.saveAndFlush(state);
    }

    private JsonNode dashboardEntry(String machineId) throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/machines").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode row : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (row.get("machineId").asText().equals(machineId)) {
                return row;
            }
        }
        throw new AssertionError("machine " + machineId + " missing from dashboard");
    }

    private void postFrame(TestCamera camera, String rawText) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", camera.deviceId());
        payload.put("captured_at", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        payload.put("confidence", 0.95);
        payload.put("raw_text", rawText);
        payload.put("frame_hash", "frame-" + UUID.randomUUID());

        mockMvc.perform(post("/ingest/ocr")
                        .header("X-Api-Key", camera.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
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

    private static String unique() {
        return System.nanoTime() + "-" + SUFFIX.incrementAndGet();
    }

    private record TestCamera(String deviceId, String apiKey, String machineId) {
    }
}
