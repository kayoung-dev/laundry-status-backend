package dev.washon.machine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import dev.washon.machine.domain.MachineCurrentState;
import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.MachineType;
import dev.washon.machine.infrastructure.CameraRepository;
import dev.washon.machine.infrastructure.MachineCurrentStateRepository;
import dev.washon.machine.infrastructure.MachineRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Rewrites apps/api/test/state-machine-timing.e2e-spec.ts for the no-"done"
 * state model: a finishing machine goes straight to available, and its
 * camera's keepalive dark frames keep it there (fresh, never stale), while a
 * silent machine is demoted to unknown after the stale threshold.
 *
 * <p>The original waited out the real 60s production default. Here the delay
 * is scaled to {@value #DELAY_MS}ms (one keepalive after
 * {@value #KEEPALIVE_AFTER_MS}ms) so the build does not block for a
 * minute; the 60s default itself is asserted from the property binding in
 * MachineStateServiceTest.
 */
@SpringBootTest(properties = {
        "washon.state-machine.stale-unknown=" + MachineStateTimingTest.DELAY_MS + "ms"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MachineStateTimingTest {

    static final long DELAY_MS = 4000;
    static final long KEEPALIVE_AFTER_MS = 2500;
    static final long WAIT_MARGIN_MS = 1200;

    private static final AtomicInteger SUFFIX = new AtomicInteger();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;
    @Autowired CameraRepository cameras;
    @Autowired MachineCurrentStateRepository currentStates;
    @Autowired JdbcTemplate jdbc;

    @Test
    void finishesStraightToAvailableAndDemotesASilentMachineToUnknownAfterTheStaleDelay() throws Exception {
        TestCamera finishing = seedCamera("timing-finish");
        TestCamera silent = seedCamera("timing-stale");

        postFrame(finishing, "05");
        postFrame(finishing, "04");
        postFrame(finishing, "02");
        assertThat(currentState(finishing).getState()).isEqualTo(MachineState.in_use);

        // The display going dark ends the cycle: available at once, no grace period.
        postFrame(finishing, "");
        MachineCurrentState finished = currentState(finishing);
        assertThat(finished.getState()).isEqualTo(MachineState.available);
        assertThat(finished.getRemainingMin()).isNull();

        postFrame(silent, "28");
        postFrame(silent, "25");
        postFrame(silent, "22");
        assertThat(currentState(silent).getState()).isEqualTo(MachineState.in_use);

        long silentSince = System.currentTimeMillis();

        // A keepalive dark frame keeps the finished machine fresh (its stale
        // timer restarts) without re-committing it; the silent camera says
        // nothing. One keepalive only: the per-camera ingest bucket holds 5
        // frames and this camera has already sent 4.
        Thread.sleep(KEEPALIVE_AFTER_MS);
        postFrame(finishing, "");
        assertThat(currentState(finishing).getState()).isEqualTo(MachineState.available);

        // Past the silent machine's stale deadline, still before the finished
        // machine's (restarted by the keepalive above).
        Thread.sleep(Math.max(0, silentSince + DELAY_MS + WAIT_MARGIN_MS - System.currentTimeMillis()));
        assertThat(currentState(finishing).getState()).isEqualTo(MachineState.available);

        MachineCurrentState stale = currentState(silent);
        assertThat(stale.getState()).isEqualTo(MachineState.unknown);
        assertThat(stale.getStaleSince()).isEqualTo(stale.getLastFrameAt());

        assertThat(systemStates(finishing)).containsExactly("in_use", "available");
        assertThat(systemStates(silent)).containsExactly("in_use", "unknown");
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

    private MachineCurrentState currentState(TestCamera camera) {
        return currentStates.findById(camera.machineId()).orElseThrow();
    }

    private List<String> systemStates(TestCamera camera) {
        return jdbc.queryForList(
                "SELECT state FROM machine_state_events WHERE machine_id = ? AND source = 'system' ORDER BY version",
                String.class, camera.machineId());
    }

    private TestCamera seedCamera(String label) {
        String suffix = label + "-" + System.nanoTime() + "-" + SUFFIX.incrementAndGet();

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

    private record TestCamera(String deviceId, String apiKey, String machineId) {
    }
}
