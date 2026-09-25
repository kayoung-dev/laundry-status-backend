package dev.washon.machine.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.washon.TestcontainersConfiguration;
import dev.washon.admin.application.AdminService;
import dev.washon.admin.payload.response.OverrideResponse;
import dev.washon.auth.domain.AdminRole;
import dev.washon.auth.domain.AuthenticatedAdmin;
import dev.washon.common.crypto.ScryptUtil;
import dev.washon.common.redis.RedisLockService;
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
import dev.washon.machine.payload.request.OcrIngestRequest;
import dev.washon.machine.payload.response.OcrIngestResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * SB-006: an admin override races OCR-driven commits on the SAME
 * {@code machine:<id>} lock-wrapper ({@link MachineStateService}) and
 * commit bean ({@link MachineStateCommitService}) as SB-002 -- same rigor as
 * MachineStateServiceTest.Ac5: real ingestion path, real MariaDB + Redis,
 * every thread released by one start gate, then the event log is checked for
 * an unbroken version run with the override landing at exactly one point.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminOverrideConcurrencyTest {

    private static final int FRAMES = 12;
    private static final int ROUNDS = 4;
    private static final AtomicInteger SUFFIX = new AtomicInteger();

    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @Autowired MachineRepository machines;
    @Autowired CameraRepository cameras;
    @Autowired MachineCurrentStateRepository currentStates;
    @Autowired JdbcTemplate jdbc;
    @Autowired OcrIngestService ingest;
    @Autowired AdminService adminService;
    @Autowired MachineStateService machineStateService;
    @Autowired MachineStateCommitService commitService;
    @Autowired RedisLockService locks;

    private final AuthenticatedAdmin admin =
            new AuthenticatedAdmin("admin-concurrency", "concurrency@washon.test", AdminRole.super_admin, null);

    private Instant clock;

    @BeforeEach
    void resetClock() {
        clock = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Override set vs a burst of OCR frames that would each transition the
     * machine. Whatever order the lock grants: frames committed before the
     * override transition normally, the override lands at exactly one
     * version, and every frame after it is suppressed (no derived row).
     */
    @Test
    void overrideAndConcurrentOcrFramesSerializeWithNoLostUpdate() throws Exception {
        List<Integer> overridePositions = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            TestCamera camera = seedCamera("race-set-" + round);

            List<Callable<Object>> tasks = frameTasks(camera);
            tasks.add(() -> adminService.setOverride(camera.machineId(), "maintenance", admin));
            runConcurrently(tasks);

            List<Derived> derived = derivedEvents(camera);
            assertUnbrokenVersionRun(derived);

            List<Derived> overrides = derived.stream().filter(d -> d.source().equals("admin_override")).toList();
            assertThat(overrides).hasSize(1);
            int overrideVersion = overrides.get(0).version();
            overridePositions.add(overrideVersion);

            List<Derived> transitions = derived.stream().filter(d -> d.source().equals("system")).toList();
            // Suppression: no OCR-driven transition committed after the override.
            assertThat(transitions).allSatisfy(t -> assertThat(t.version()).isLessThan(overrideVersion));
            assertThat(transitions).extracting(Derived::errorCode).doesNotHaveDuplicates();
            assertThat(transitions).hasSize(overrideVersion - 2);

            MachineCurrentState current = currentStates.findById(camera.machineId()).orElseThrow();
            assertThat(current.getVersion()).isEqualTo(overrideVersion);
            assertThat(current.getState()).isEqualTo(MachineState.maintenance);
            assertThat(current.isOverrideActive()).isTrue();
            assertThat(current.getOverrideBy()).isEqualTo(admin.email());
            assertThat(current.getRemainingMin()).isNull();
            assertThat(current.getErrorCode()).isNull();
            // Every frame was still ingested (raw rows), suppressed or not.
            assertThat(countEvents(camera.machineId(), "ocr")).isEqualTo(FRAMES);
        }
        // Each round's override sits somewhere in 2..FRAMES+2 (recorded for diagnostics on failure).
        assertThat(overridePositions).allSatisfy(v -> assertThat(v).isBetween(2, FRAMES + 2));
    }

    /**
     * Override clear vs the same burst, starting from an active override.
     * Frames before the clear are suppressed; frames after it resume
     * inference -- so every derived transition is newer than the clear.
     */
    @Test
    void overrideClearAndConcurrentOcrFramesSerializeAndInferenceResumesOnlyAfterTheClear() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            TestCamera camera = seedCamera("race-clear-" + round);
            adminService.setOverride(camera.machineId(), "maintenance", admin);

            List<Callable<Object>> tasks = frameTasks(camera);
            tasks.add(() -> adminService.clearOverride(camera.machineId(), admin));
            runConcurrently(tasks);

            List<Derived> derived = derivedEvents(camera);
            assertUnbrokenVersionRun(derived);

            List<Derived> overrides = derived.stream().filter(d -> d.source().equals("admin_override")).toList();
            assertThat(overrides).extracting(Derived::version).containsExactly(2, 3);
            List<Derived> transitions = derived.stream().filter(d -> d.source().equals("system")).toList();
            assertThat(transitions).allSatisfy(t -> assertThat(t.version()).isGreaterThan(3));
            assertThat(transitions).extracting(Derived::errorCode).doesNotHaveDuplicates();

            MachineCurrentState current = currentStates.findById(camera.machineId()).orElseThrow();
            assertThat(current.isOverrideActive()).isFalse();
            assertThat(current.getOverrideBy()).isNull();
            assertThat(current.getVersion()).isEqualTo(derived.get(derived.size() - 1).version());
            if (transitions.isEmpty()) {
                assertThat(current.getState()).isEqualTo(MachineState.available);
                assertThat(current.getRemainingMin()).isNull();
                assertThat(current.getErrorCode()).isNull();
            } else {
                assertThat(current.getState()).isEqualTo(MachineState.error);
                assertThat(current.getErrorCode()).isEqualTo(transitions.get(transitions.size() - 1).errorCode());
            }
        }
    }

    /**
     * Proves the override contends on the very same Redis key as OCR commits
     * (not a parallel lock): while the test itself holds {@code machine:<id>},
     * neither the override nor an OCR frame can commit; releasing it lets both
     * through, serialized.
     */
    @Test
    void overrideBlocksOnTheSameMachineLockKeyAsOcrCommits() throws Exception {
        TestCamera camera = seedCamera("same-key");
        Camera cameraRow = cameras.findByDeviceId(camera.deviceId()).orElseThrow();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> inFlight = new ArrayList<>();
            locks.withLock("machine:" + camera.machineId(), Duration.ofSeconds(10), Duration.ofSeconds(1), () -> {
                inFlight.add(pool.submit(() -> adminService.setOverride(camera.machineId(), "maintenance", admin)));
                inFlight.add(pool.submit(() -> ingest.persist(cameraRow, frame(camera, "E01"))));
                sleep(1500);
                // Both are parked on the lock: nothing committed.
                assertThat(inFlight).noneMatch(Future::isDone);
                assertThat(countEvents(camera.machineId(), "admin_override")).isZero();
                assertThat(countEvents(camera.machineId(), "system")).isZero();
                return null;
            });
            for (Future<?> f : inFlight) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        List<Derived> derived = derivedEvents(camera);
        assertUnbrokenVersionRun(derived);
        assertThat(derived.stream().filter(d -> d.source().equals("admin_override")).count()).isEqualTo(1);
    }

    @Test
    void overrideGoesThroughTheLockWrapperAndTheTransactionalCommitBean() throws Exception {
        assertThat(AopUtils.isAopProxy(commitService)).isTrue();
        assertThat(AopUtils.getTargetClass(machineStateService)).isEqualTo(MachineStateService.class);
        for (String name : new String[] {"setOverride", "clearOverride"}) {
            assertThat(Arrays.stream(MachineStateService.class.getMethods())
                    .filter(m -> m.getName().equals(name))
                    .anyMatch(m -> m.isAnnotationPresent(Transactional.class))).isFalse();
        }
        assertThat(MachineStateCommitService.class
                .getMethod("commitOverride", String.class, MachineState.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(MachineStateCommitService.class
                .getMethod("commitOverrideClear", String.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        // The admin layer has no lock or commit path of its own: it can only
        // reach machine state through MachineStateService.
        List<Class<?>> deps = Arrays.asList(AdminService.class.getConstructors()[0].getParameterTypes());
        assertThat(deps).contains(MachineStateService.class)
                .doesNotContain(RedisLockService.class, MachineStateCommitService.class);
    }

    // ---------------------------------------------------------------- helpers

    private List<Callable<Object>> frameTasks(TestCamera camera) {
        Camera cameraRow = cameras.findByDeviceId(camera.deviceId()).orElseThrow();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < FRAMES; i++) {
            OcrIngestRequest frame = frame(camera, String.format("E%02d", i + 1));
            // Bypasses HTTP auth + rate limit only (as in MachineStateServiceTest.Ac5).
            tasks.add(() -> {
                OcrIngestResponse response = ingest.persist(cameraRow, frame);
                assertThat(response.status()).isEqualTo("accepted");
                assertThat(response.duplicate()).isFalse();
                return response;
            });
        }
        return tasks;
    }

    private OcrIngestRequest frame(TestCamera camera, String rawText) {
        clock = clock.plusSeconds(1);
        return new OcrIngestRequest(camera.deviceId(), clock, 0.95, rawText, "frame-" + UUID.randomUUID());
    }

    /** Releases every task at once through a start gate; fails if any task threw. */
    private static void runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        try {
            for (Callable<Object> task : tasks) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Object> result : results) {
                Object value = result.get(30, TimeUnit.SECONDS);
                if (value instanceof OverrideResponse response) {
                    assertThat(response.version()).isPositive();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** One writer at a time: versions across system + admin_override rows form an unbroken 2..N run. */
    private static void assertUnbrokenVersionRun(List<Derived> derived) {
        List<Integer> expected = new ArrayList<>();
        for (int v = 2; v < 2 + derived.size(); v++) {
            expected.add(v);
        }
        assertThat(derived).extracting(Derived::version).containsExactlyElementsOf(expected);
    }

    private List<Derived> derivedEvents(TestCamera camera) {
        return jdbc.query(
                "SELECT source, state, error_code, version FROM machine_state_events "
                        + "WHERE machine_id = ? AND source IN ('system', 'admin_override') ORDER BY version ASC",
                (rs, i) -> new Derived(rs.getString("source"), rs.getString("state"), rs.getString("error_code"),
                        rs.getInt("version")),
                camera.machineId());
    }

    private int countEvents(String machineId, String source) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM machine_state_events WHERE machine_id = ? AND source = ?",
                Integer.class, machineId, source);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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

        return new TestCamera(deviceId, machine.getId());
    }

    record TestCamera(String deviceId, String machineId) {
    }

    record Derived(String source, String state, String errorCode, int version) {
    }
}
