package dev.washon.machine.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.washon.TestcontainersConfiguration;
import dev.washon.facility.domain.Room;
import dev.washon.facility.domain.Site;
import dev.washon.facility.domain.Tenant;
import dev.washon.facility.infrastructure.RoomRepository;
import dev.washon.facility.infrastructure.SiteRepository;
import dev.washon.facility.infrastructure.TenantRepository;
import dev.washon.machine.domain.Machine;
import dev.washon.machine.domain.MachineType;
import dev.washon.machine.infrastructure.MachineRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P2-001: GET /machines/{machineId}/qr-target resolves a stable, no-auth QR
 * deep link for a machine (dev.washon.machine.api.MachineQrTargetApi), using
 * real MariaDB (Testcontainers) instead of mocks, following the seeding
 * style of dev.washon.report.api.ReportApiTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MachineQrTargetApiTest {

    private static final AtomicInteger SUFFIX = new AtomicInteger();

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;

    @Test
    void resolvesADeterministicRoomDeepLinkForAKnownSeededMachineNoAuthRequired() throws Exception {
        Machine machine = seedMachine("known");

        mockMvc.perform(get("/machines/{machineId}/qr-target", machine.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value(machine.getId()))
                .andExpect(jsonPath("$.roomId").value(machine.getRoomId()))
                .andExpect(jsonPath("$.url").value(
                        "http://localhost:3000/room/" + machine.getRoomId() + "?machine=" + machine.getId()));
    }

    @Test
    void resolvesTheSameTargetOnRepeatedCallsInsteadOfRandomlyRegeneratingIt() throws Exception {
        Machine machine = seedMachine("stable");

        String first = mockMvc.perform(get("/machines/{machineId}/qr-target", machine.getId()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String second = mockMvc.perform(get("/machines/{machineId}/qr-target", machine.getId()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void returns404ForAnUnknownMachineId() throws Exception {
        mockMvc.perform(get("/machines/{machineId}/qr-target", "no-such-machine-" + unique()))
                .andExpect(status().isNotFound());
    }

    private Machine seedMachine(String label) {
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

        return machine;
    }

    private static String unique() {
        return System.nanoTime() + "-" + SUFFIX.incrementAndGet();
    }
}
