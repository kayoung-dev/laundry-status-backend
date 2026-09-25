package dev.washon.common.seed;

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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal dev seed, the Java replacement for the deleted
 * {@code packages/db/prisma/seed.ts}: one Tenant -> Site -> Room -> three
 * Machine -> Camera chains (so the room view / admin dashboard have more than
 * one card to look at) plus one AdminUser, with the same fixed ids.
 *
 * <p>Runs only under the {@code seed} profile (which also disables the web
 * server, so the process seeds and exits):
 * {@code ./gradlew bootRun --args='--spring.profiles.active=seed'}.
 *
 * <p>Every camera's API key and the admin password are freshly randomised on
 * every run, printed once, and written to the gitignored
 * {@code .seed-api-key} / {@code .seed-api-keys.txt} / {@code
 * .seed-admin-credentials} files in the working directory. Only their scrypt
 * hashes reach the database.
 */
@Component
@Profile("seed")
public class DevSeedRunner implements CommandLineRunner {

    static final String ADMIN_EMAIL = "admin@washon.dev";

    /** device_id of the machine whose key is also mirrored to {@code .seed-api-key}, for scripts/tools with no multi-machine awareness (e.g. camera-simulator.ts's default). */
    private static final String PRIMARY_DEVICE_ID = "seed-device-1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private record MachineSeed(String machineId, String cameraId, String deviceId, String label) {
    }

    private static final List<MachineSeed> MACHINE_SEEDS = List.of(
            new MachineSeed("seed-machine-1", "seed-camera-1", "seed-device-1", "Washer 1"),
            new MachineSeed("seed-machine-2", "seed-camera-2", "seed-device-2", "Washer 2"),
            new MachineSeed("seed-machine-3", "seed-camera-3", "seed-device-3", "Washer 3"));

    private final TenantRepository tenants;
    private final SiteRepository sites;
    private final RoomRepository rooms;
    private final MachineRepository machines;
    private final CameraRepository cameras;
    private final AdminUserRepository adminUsers;

    public DevSeedRunner(TenantRepository tenants, SiteRepository sites, RoomRepository rooms,
            MachineRepository machines, CameraRepository cameras, AdminUserRepository adminUsers) {
        this.tenants = tenants;
        this.sites = sites;
        this.rooms = rooms;
        this.machines = machines;
        this.cameras = cameras;
        this.adminUsers = adminUsers;
    }

    @Override
    @Transactional
    public void run(String... args) throws IOException {
        Tenant tenant = tenants.findById("seed-tenant").orElseGet(() -> {
            Tenant t = new Tenant();
            t.setId("seed-tenant");
            t.setName("WashOn Dev Tenant");
            return tenants.save(t);
        });

        Site site = sites.findById("seed-site").orElseGet(() -> {
            Site s = new Site();
            s.setId("seed-site");
            s.setTenantId(tenant.getId());
            s.setName("Dev Campus");
            return sites.save(s);
        });

        Room room = rooms.findById("seed-room").orElseGet(() -> {
            Room r = new Room();
            r.setId("seed-room");
            r.setSiteId(site.getId());
            r.setName("Dorm A Laundry Room");
            return rooms.save(r);
        });

        List<String> apiKeyLines = new ArrayList<>();
        String primaryApiKey = null;

        for (MachineSeed seed : MACHINE_SEEDS) {
            Machine machine = machines.findById(seed.machineId()).orElseGet(() -> {
                Machine m = new Machine();
                m.setId(seed.machineId());
                m.setRoomId(room.getId());
                m.setType(MachineType.washer);
                m.setLabel(seed.label());
                return machines.save(m);
            });

            String plainApiKey = "dev_" + randomHex(24);
            Camera camera = cameras.findByMachineId(machine.getId()).orElseGet(() -> {
                Camera c = new Camera();
                c.setId(seed.cameraId());
                c.setMachineId(machine.getId());
                c.setDeviceId(seed.deviceId());
                c.setStatus("online");
                return c;
            });
            camera.setApiKeyHash(ScryptUtil.hashSecret(plainApiKey));
            cameras.save(camera);

            apiKeyLines.add(seed.deviceId() + "=" + plainApiKey);
            if (seed.deviceId().equals(PRIMARY_DEVICE_ID)) {
                primaryApiKey = plainApiKey;
            }
        }

        // Re-randomised on every run so a stale password cannot linger as a
        // known credential.
        String plainAdminPassword = "dev_" + randomHex(12);
        AdminUser admin = adminUsers.findByEmail(ADMIN_EMAIL).orElseGet(() -> {
            AdminUser a = new AdminUser();
            a.setId("seed-admin-1");
            a.setEmail(ADMIN_EMAIL);
            return a;
        });
        admin.setPasswordHash(ScryptUtil.hashSecret(plainAdminPassword));
        admin.setRole(AdminRole.super_admin);
        admin.setSiteScopeId(site.getId());
        adminUsers.save(admin);

        Path apiKeyPath = Path.of(".seed-api-key").toAbsolutePath();
        Files.writeString(apiKeyPath, primaryApiKey + "\n", StandardCharsets.UTF_8);
        Path apiKeysPath = Path.of(".seed-api-keys.txt").toAbsolutePath();
        Files.writeString(apiKeysPath, String.join("\n", apiKeyLines) + "\n", StandardCharsets.UTF_8);
        Path adminPath = Path.of(".seed-admin-credentials").toAbsolutePath();
        Files.writeString(adminPath, ADMIN_EMAIL + "\n" + plainAdminPassword + "\n", StandardCharsets.UTF_8);

        System.out.println("Seeded dev data:");
        System.out.println("  room_id: " + room.getId());
        for (MachineSeed seed : MACHINE_SEEDS) {
            System.out.println("  machine_id: " + seed.machineId() + " (\"" + seed.label() + "\"), device_id: " + seed.deviceId());
        }
        System.out.println("  API keys (plaintext, dev-only) written to " + apiKeysPath + " (one \"device_id=key\" line per machine)");
        System.out.println("  " + PRIMARY_DEVICE_ID + "'s key is also mirrored to " + apiKeyPath + " (default path read by scripts/camera-simulator.ts)");
        System.out.println("  admin email: " + ADMIN_EMAIL);
        System.out.println("  admin password (plaintext, dev-only): " + plainAdminPassword);
        System.out.println("  (also written to " + adminPath + ")");
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
