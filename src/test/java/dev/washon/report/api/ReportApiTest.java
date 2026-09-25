package dev.washon.report.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import dev.washon.machine.domain.Machine;
import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.MachineType;
import dev.washon.machine.infrastructure.MachineRepository;
import dev.washon.report.domain.StudentReport;
import dev.washon.report.infrastructure.StudentReportRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rewrites every scenario in apps/api/test/reports.e2e-spec.ts against
 * dev.washon.report.api.ReportApi, using real MariaDB (Testcontainers)
 * instead of mocks.
 *
 * <p>GET .../needs-check is admin-guarded (AdminAuthInterceptor, wired onto
 * {@code /admin/machines/**} in SB-006), so -- like the original spec once
 * admin auth landed in US-008 -- these tests log in as a seeded admin and
 * send its bearer token; the unauthenticated case is asserted to be a 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportApiTest {

    private static final AtomicInteger SUFFIX = new AtomicInteger();
    private static final long NEEDS_CHECK_WINDOW_MS = 10 * 60 * 1000;
    private static final String ADMIN_PASSWORD = "correct-horse-battery-staple";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;
    @Autowired StudentReportRepository reports;
    @Autowired AdminUserRepository adminUsers;

    private String adminToken;

    @BeforeEach
    void logInAsAdmin() throws Exception {
        AdminUser admin = new AdminUser();
        admin.setEmail("report-admin-" + unique() + "@washon.test");
        admin.setPasswordHash(ScryptUtil.hashSecret(ADMIN_PASSWORD));
        admin.setRole(AdminRole.super_admin);
        adminUsers.saveAndFlush(admin);

        MvcResult login = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", admin.getEmail(), "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        adminToken = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void acceptsAReportAndPersistsItNoAuthRequired() throws Exception {
        String machineId = seedMachine("persist");

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reportPayload(machineId, "available", "dev-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.machineId").value(machineId))
                .andExpect(jsonPath("$.reportedState").value("available"))
                .andExpect(jsonPath("$.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.id").isNotEmpty());

        var rows = reports.findAll().stream().filter(r -> r.getMachineId().equals(machineId)).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDeviceId()).isEqualTo("dev-1");
        assertThat(rows.get(0).getReportedState()).isEqualTo(MachineState.available);
    }

    @Test
    void rejectsAnInvalidPayloadWith400() throws Exception {
        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reportPayload("", "not-a-state", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns404ForNeedsCheckOnAnUnknownMachine() throws Exception {
        mockMvc.perform(get("/admin/machines/no-such-machine/needs-check").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAnUnauthenticatedNeedsCheckWith401() throws Exception {
        String machineId = seedMachine("unauth");

        mockMvc.perform(get("/admin/machines/{machineId}/needs-check", machineId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void flagsNeedsCheckTrueOnceThreeDistinctDeviceIdsReportWithinTenMinutes() throws Exception {
        String machineId = seedMachine("three-distinct");

        for (String deviceId : new String[] {"dev-a", "dev-b", "dev-c"}) {
            mockMvc.perform(post("/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reportPayload(machineId, "error", deviceId))))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/admin/machines/{machineId}/needs-check", machineId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value(machineId))
                .andExpect(jsonPath("$.needsCheck").value(true))
                .andExpect(jsonPath("$.distinctDeviceCount").value(3))
                .andExpect(jsonPath("$.windowMinutes").value(10));
    }

    @Test
    void doesNotFlagNeedsCheckForFiveReportsFromTheSameDeviceId() throws Exception {
        String machineId = seedMachine("same-device");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reportPayload(machineId, "error", "dev-solo"))))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/admin/machines/{machineId}/needs-check", machineId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value(machineId))
                .andExpect(jsonPath("$.needsCheck").value(false))
                .andExpect(jsonPath("$.distinctDeviceCount").value(1))
                .andExpect(jsonPath("$.windowMinutes").value(10));
    }

    @Test
    void doesNotCountReportsOlderThanTheTenMinuteWindow() throws Exception {
        String machineId = seedMachine("stale-window");

        for (String deviceId : new String[] {"dev-fresh-1", "dev-fresh-2"}) {
            mockMvc.perform(post("/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reportPayload(machineId, "error", deviceId))))
                    .andExpect(status().isCreated());
        }

        // A third distinct device_id, but reported well outside the window --
        // inserted directly via the repository to simulate the passage of
        // time without waiting 10 real minutes.
        StudentReport stale = new StudentReport();
        stale.setMachineId(machineId);
        stale.setReportedState(MachineState.error);
        stale.setDeviceId("dev-stale");
        stale.setCreatedAt(Instant.now().minusMillis(NEEDS_CHECK_WINDOW_MS + 60_000));
        reports.saveAndFlush(stale);

        mockMvc.perform(get("/admin/machines/{machineId}/needs-check", machineId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value(machineId))
                .andExpect(jsonPath("$.needsCheck").value(false))
                .andExpect(jsonPath("$.distinctDeviceCount").value(2))
                .andExpect(jsonPath("$.windowMinutes").value(10));
    }

    private String seedMachine(String label) {
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

        return machine.getId();
    }

    private static Map<String, Object> reportPayload(String machineId, String reportedState, String deviceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("machine_id", machineId);
        payload.put("reported_state", reportedState);
        payload.put("device_id", deviceId);
        return payload;
    }

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private static String unique() {
        return System.nanoTime() + "-" + SUFFIX.incrementAndGet();
    }
}
