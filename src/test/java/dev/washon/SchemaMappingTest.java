package dev.washon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.washon.auth.domain.AdminRole;
import dev.washon.auth.domain.AdminUser;
import dev.washon.auth.infrastructure.AdminUserRepository;
import dev.washon.facility.domain.Room;
import dev.washon.facility.domain.Site;
import dev.washon.facility.domain.Tenant;
import dev.washon.facility.infrastructure.RoomRepository;
import dev.washon.facility.infrastructure.SiteRepository;
import dev.washon.facility.infrastructure.TenantRepository;
import dev.washon.machine.domain.EventSource;
import dev.washon.machine.domain.Machine;
import dev.washon.machine.domain.MachineCurrentState;
import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.MachineStateEvent;
import dev.washon.machine.domain.MachineType;
import dev.washon.machine.infrastructure.MachineCurrentStateRepository;
import dev.washon.machine.infrastructure.MachineRepository;
import dev.washon.machine.infrastructure.MachineStateEventRepository;
import io.github.thibaultmeyer.cuid.CUID;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Boots the full context against a Flyway-migrated MariaDB with
 * ddl-auto=validate: if the entities and V1__init.sql disagree, the context
 * fails to start and every test here fails.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMappingTest {

    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;
    @Autowired MachineStateEventRepository events;
    @Autowired MachineCurrentStateRepository currentStates;
    @Autowired AdminUserRepository adminUsers;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatedAllNineTablesWithVarcharEnums() {
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                        + " AND table_name <> 'flyway_schema_history'",
                String.class))
                .containsExactlyInAnyOrder("tenants", "sites", "rooms", "machines", "cameras",
                        "machine_state_events", "machine_current_states", "student_reports", "admin_users");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()"
                        + " AND data_type = 'enum'",
                Integer.class))
                .isZero();
    }

    @Test
    void generatesCuidIdsAndPersistsTheHierarchy() {
        Machine machine = newMachine();

        assertThat(CUID.isValid(machine.getId())).isTrue();
        assertThat(machine.getCreatedAt()).isNotNull();
        assertThat(machine.getUpdatedAt()).isNotNull();
        assertThat(machines.findById(machine.getId()))
                .get()
                .extracting(Machine::getType)
                .isEqualTo(MachineType.washer);
        assertThat(jdbc.queryForObject("SELECT type FROM machines WHERE id = ?", String.class, machine.getId()))
                .isEqualTo("washer");

        MachineCurrentState state = new MachineCurrentState();
        state.setMachineId(machine.getId());
        currentStates.saveAndFlush(state);
        assertThat(currentStates.findById(machine.getId()))
                .get()
                .satisfies(s -> {
                    assertThat(s.getState()).isEqualTo(MachineState.unknown);
                    assertThat(s.getVersion()).isEqualTo(1);
                    assertThat(s.isOverrideActive()).isFalse();
                });

        AdminUser admin = new AdminUser();
        admin.setEmail("schema-test-" + machine.getId() + "@washon.dev");
        admin.setPasswordHash("x:y");
        admin.setRole(AdminRole.technician);
        adminUsers.saveAndFlush(admin);
        assertThat(CUID.isValid(admin.getId())).isTrue();
    }

    @Test
    void duplicateFrameHashForSameMachineIsRejectedWithMariaDbDupEntry() {
        Machine machine = newMachine();
        events.saveAndFlush(event(machine.getId(), "frame-1"));

        assertThatThrownBy(() -> events.saveAndFlush(event(machine.getId(), "frame-1")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLIntegrityConstraintViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(1062));

        // NULL frame hashes never collide (no de-duplication without a hash).
        events.saveAndFlush(event(machine.getId(), null));
        events.saveAndFlush(event(machine.getId(), null));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM machine_state_events WHERE machine_id = ?", Integer.class, machine.getId()))
                .isEqualTo(3);
    }

    private Machine newMachine() {
        Tenant tenant = new Tenant();
        tenant.setName("t");
        tenants.saveAndFlush(tenant);
        Site site = new Site();
        site.setTenantId(tenant.getId());
        site.setName("s");
        sites.saveAndFlush(site);
        Room room = new Room();
        room.setSiteId(site.getId());
        room.setName("r");
        rooms.saveAndFlush(room);
        Machine machine = new Machine();
        machine.setRoomId(room.getId());
        machine.setType(MachineType.washer);
        machine.setLabel("Washer");
        return machines.saveAndFlush(machine);
    }

    private static MachineStateEvent event(String machineId, String frameHash) {
        MachineStateEvent e = new MachineStateEvent();
        e.setMachineId(machineId);
        e.setSource(EventSource.ocr);
        e.setState(MachineState.in_use);
        e.setRemainingMin(12);
        e.setConfidence(0.93);
        e.setFrameHash(frameHash);
        return e;
    }
}
