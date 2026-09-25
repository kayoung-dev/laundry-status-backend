package dev.washon.machine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import dev.washon.machine.domain.WashStage;
import dev.washon.machine.infrastructure.CameraRepository;
import dev.washon.machine.infrastructure.MachineCurrentStateRepository;
import dev.washon.machine.infrastructure.MachineRepository;
import dev.washon.machine.payload.request.OcrIngestRequest;
import dev.washon.machine.payload.response.OcrIngestResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rewrites every scenario in apps/api/test/state-machine.e2e-spec.ts against
 * the real ingestion path (POST /ingest/ocr -> OcrIngestService ->
 * MachineStateService -> MachineStateCommitService), with real MariaDB +
 * Redis (Testcontainers).
 *
 * <p>The two delayed transitions are shortened so the suite does not spend a
 * real minute per assertion; MachineStateTimingTest covers the grace-period
 * semantics of the timing spec, and the 60s production defaults are asserted
 * directly from the property binding below.
 */
@SpringBootTest(properties = {
        "washon.state-machine.done-to-available=" + MachineStateServiceTest.DONE_TO_AVAILABLE_MS + "ms",
        "washon.state-machine.stale-unknown=" + MachineStateServiceTest.STALE_UNKNOWN_MS + "ms"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MachineStateServiceTest {

    static final long DONE_TO_AVAILABLE_MS = 1000;
    static final long STALE_UNKNOWN_MS = 5000;

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
    @Autowired OcrIngestService ingest;
    @Autowired MachineStateService machineStateService;
    @Autowired MachineStateCommitService commitService;
    @Autowired StateMachineProperties properties;

    /** Base capture time; frames get strictly increasing captured_at from it. */
    private Instant clock;

    @BeforeEach
    void resetClock() {
        clock = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    @Nested
    class Ac1ThreeConsecutiveDecreasingFramesToInUse {

        @Test
        void waitsForThreeFrameConsensusThenTracksTheLatestRemainingMin() throws Exception {
            TestCamera camera = seedCamera("in-use");

            postFrame(camera, "28");
            assertThat(currentState(camera).getState()).isEqualTo(MachineState.unknown);

            postFrame(camera, "25");
            assertThat(currentState(camera).getState()).isEqualTo(MachineState.unknown);

            postFrame(camera, "22");
            MachineCurrentState afterConsensus = currentState(camera);
            assertThat(afterConsensus.getState()).isEqualTo(MachineState.in_use);
            assertThat(afterConsensus.getRemainingMin()).isEqualTo(22);

            postFrame(camera, "19");
            MachineCurrentState latest = currentState(camera);
            assertThat(latest.getState()).isEqualTo(MachineState.in_use);
            assertThat(latest.getRemainingMin()).isEqualTo(19);

            // Derived transitions are separate, auditable system rows...
            assertThat(systemEvents(camera))
                    .extracting(SystemEvent::state, SystemEvent::remainingMin, SystemEvent::version)
                    .containsExactly(tuple("in_use", 22, 2), tuple("in_use", 19, 3));

            // ...and the raw OCR rows keep their ingestion-time placeholder.
            List<String> rawStates = jdbc.queryForList(
                    "SELECT state FROM machine_state_events WHERE machine_id = ? AND source = 'ocr'",
                    String.class, camera.machineId());
            assertThat(rawStates).hasSize(4).containsOnly("unknown");
        }

        @Test
        void doesNotTransitionOnAnInconsistentNoisyWindow() throws Exception {
            TestCamera camera = seedCamera("noise-spike");

            postFrame(camera, "30");
            postFrame(camera, "28");
            postFrame(camera, "--"); // unreadable frame: breaks consensus
            assertThat(currentState(camera).getState()).isEqualTo(MachineState.unknown);

            postFrame(camera, "24"); // window still contains the noise frame
            assertThat(currentState(camera).getState()).isEqualTo(MachineState.unknown);

            postFrame(camera, "22"); // window is [--, 24, 22]: still tainted
            assertThat(currentState(camera).getState()).isEqualTo(MachineState.unknown);
        }

        @Test
        void treatsALowConfidenceFrameAsNoise() throws Exception {
            TestCamera camera = seedCamera("low-confidence");

            postFrame(camera, "30");
            postFrame(camera, "28");
            postFrame(camera, "26", 0.2);

            assertThat(currentState(camera).getState()).isEqualTo(MachineState.unknown);
        }
    }

    @Nested
    class Ac2BlankOrZeroDisplayToDoneThenAvailable {

        @Test
        void endsTheCycleOnADarkDisplayAndReleasesToAvailable() throws Exception {
            TestCamera camera = seedCamera("done");

            postFrame(camera, "05");
            postFrame(camera, "02");
            postFrame(camera, "");

            MachineCurrentState done = currentState(camera);
            assertThat(done.getState()).isEqualTo(MachineState.done);
            assertThat(done.getRemainingMin()).isZero();

            Thread.sleep(DONE_TO_AVAILABLE_MS + 700);

            MachineCurrentState released = currentState(camera);
            assertThat(released.getState()).isEqualTo(MachineState.available);
            assertThat(released.getRemainingMin()).isNull();

            assertThat(systemEvents(camera)).extracting(SystemEvent::state).containsExactly("done", "available");
        }

        @Test
        void treatsAZeroValueDisplayTheSameAsABlankOne() throws Exception {
            TestCamera camera = seedCamera("zero");

            postFrame(camera, "03");
            postFrame(camera, "01");
            postFrame(camera, "00");

            assertThat(currentState(camera).getState()).isEqualTo(MachineState.done);
        }

        @Test
        void readsADisplayThatWasNeverCountingDownAsAnIdleMachine() throws Exception {
            TestCamera camera = seedCamera("idle");

            postFrame(camera, "");
            postFrame(camera, "");

            assertThat(currentState(camera).getState()).isEqualTo(MachineState.available);
            // No flapping: the second dark frame changed nothing.
            assertThat(systemEvents(camera)).extracting(SystemEvent::state).containsExactly("available");
        }
    }

    @Nested
    class Ac3ErrorTextToErrorWithCodeStored {

        @Test
        void storesTheErrorCodeOnTheCurrentStateAndOnTheDerivedEvent() throws Exception {
            TestCamera camera = seedCamera("error");

            postFrame(camera, "12");
            postFrame(camera, "E01");

            MachineCurrentState errored = currentState(camera);
            assertThat(errored.getState()).isEqualTo(MachineState.error);
            assertThat(errored.getErrorCode()).isEqualTo("E01");

            postFrame(camera, "F-21");
            MachineCurrentState changed = currentState(camera);
            assertThat(changed.getState()).isEqualTo(MachineState.error);
            assertThat(changed.getErrorCode()).isEqualTo("F21");

            assertThat(systemEvents(camera))
                    .extracting(SystemEvent::state, SystemEvent::errorCode)
                    .containsExactly(tuple("error", "E01"), tuple("error", "F21"));
        }
    }

    @Nested
    class Ac4SilenceToUnknownWithStalenessData {

        @Test
        void demotesAMachineToUnknownOnceNoFrameHasArrivedForTheThreshold() throws Exception {
            TestCamera camera = seedCamera("stale");

            postFrame(camera, "28");
            postFrame(camera, "25");
            postFrame(camera, "22");

            MachineCurrentState running = currentState(camera);
            assertThat(running.getState()).isEqualTo(MachineState.in_use);
            assertThat(running.getLastFrameAt()).isNotNull();
            assertThat(running.getStaleSince()).isNull();

            Thread.sleep(STALE_UNKNOWN_MS + 1500);

            MachineCurrentState stale = currentState(camera);
            assertThat(stale.getState()).isEqualTo(MachineState.unknown);
            assertThat(stale.getRemainingMin()).isNull();
            assertThat(stale.getStaleSince()).isEqualTo(running.getLastFrameAt());
            assertThat(stale.getLastFrameAt()).isEqualTo(running.getLastFrameAt());

            assertThat(systemEvents(camera)).extracting(SystemEvent::state).containsExactly("in_use", "unknown");
        }

        @Test
        void exposesTheFiveMinuteCameraDownThresholdForSb007ToConsume() {
            assertThat(properties.cameraDown()).isEqualTo(Duration.ofMinutes(5));

            // The shipped defaults, bound with no overrides at all.
            StateMachineProperties defaults = new Binder()
                    .bindOrCreate("washon.state-machine", StateMachineProperties.class);
            assertThat(defaults.staleUnknown()).isEqualTo(Duration.ofSeconds(60));
            assertThat(defaults.doneToAvailable()).isEqualTo(Duration.ofSeconds(60));
            assertThat(defaults.cameraDown()).isEqualTo(Duration.ofMinutes(5));
            assertThat(defaults.minConfidence()).isEqualTo(0.5);
        }
    }

    @Nested
    class Ac5ConcurrentUpdatesToOneMachineAreSerialized {

        private static final int FRAMES = 12;

        @Test
        void appliesEveryOverlappingUpdateExactlyOnceWithNoLostOrInterleavedWrite() throws Exception {
            TestCamera testCamera = seedCamera("concurrency");
            Camera camera = cameras.findByDeviceId(testCamera.deviceId()).orElseThrow();

            List<OcrIngestRequest> frames = new ArrayList<>();
            for (int i = 0; i < FRAMES; i++) {
                frames.add(new OcrIngestRequest(testCamera.deviceId(), nextCapturedAt(), 0.95,
                        String.format("E%02d", i + 1), "frame-" + UUID.randomUUID()));
            }

            // Bypasses HTTP auth + rate limit (and only those) so the per-camera
            // bucket does not cap the burst: this is the real ingestion + state
            // machine path. All threads are released at once by the start gate.
            ExecutorService pool = Executors.newFixedThreadPool(FRAMES);
            CountDownLatch ready = new CountDownLatch(FRAMES);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<OcrIngestResponse>> results = new ArrayList<>();
            try {
                for (OcrIngestRequest frame : frames) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return ingest.persist(camera, frame);
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (Future<OcrIngestResponse> result : results) {
                    OcrIngestResponse response = result.get(30, TimeUnit.SECONDS);
                    assertThat(response.status()).isEqualTo("accepted");
                    assertThat(response.duplicate()).isFalse();
                }
            } finally {
                pool.shutdownNow();
            }

            List<SystemEvent> derived = systemEvents(testCamera);
            MachineCurrentState current = currentState(testCamera);

            // One writer at a time: versions form an unbroken 2..13 run (a lost
            // read-modify-write would repeat or skip one), every frame produced
            // exactly one transition, and the materialized row matches the last
            // event in the log.
            List<Integer> expectedVersions = new ArrayList<>();
            for (int v = 2; v <= FRAMES + 1; v++) {
                expectedVersions.add(v);
            }
            assertThat(derived).extracting(SystemEvent::version).containsExactlyElementsOf(expectedVersions);
            assertThat(derived).extracting(SystemEvent::errorCode).doesNotHaveDuplicates().hasSize(FRAMES);
            assertThat(current.getVersion()).isEqualTo(FRAMES + 1);
            assertThat(current.getState()).isEqualTo(MachineState.error);
            assertThat(current.getErrorCode()).isEqualTo(derived.get(derived.size() - 1).errorCode());
        }

        @Test
        void locksOutsideTheTransactionThroughTwoSeparateBeans() throws Exception {
            // The commit bean is a transactional proxy; the lock wrapper is a
            // different class that is not transactional itself.
            assertThat(AopUtils.isAopProxy(commitService)).isTrue();
            assertThat(AopUtils.getTargetClass(machineStateService)).isEqualTo(MachineStateService.class);
            assertThat(MachineStateService.class).isNotEqualTo(MachineStateCommitService.class);
            assertThat(MachineStateService.class.isAnnotationPresent(Transactional.class)).isFalse();
            assertThat(MachineStateService.class
                    .getMethod("handleOcrFrame", String.class, IngestedOcrFrame.class)
                    .isAnnotationPresent(Transactional.class)).isFalse();
            assertThat(MachineStateCommitService.class
                    .getMethod("applyOcrFrame", String.class, IngestedOcrFrame.class)
                    .isAnnotationPresent(Transactional.class)).isTrue();
        }
    }

    @Nested
    class Ac6ActiveAdminOverrideSuppressesOcrTransitions {

        @Test
        void keepsTheOverriddenStateWhileStillTrackingCameraLiveness() throws Exception {
            TestCamera camera = seedCamera("override");
            MachineCurrentState overridden = new MachineCurrentState();
            overridden.setMachineId(camera.machineId());
            overridden.setState(MachineState.maintenance);
            overridden.setOverrideActive(true);
            overridden.setOverrideBy("admin-under-test");
            currentStates.saveAndFlush(overridden);

            postFrame(camera, "28");
            postFrame(camera, "25");
            postFrame(camera, "22");
            postFrame(camera, "19");

            MachineCurrentState state = currentState(camera);
            assertThat(state.getState()).isEqualTo(MachineState.maintenance);
            assertThat(state.getRemainingMin()).isNull();
            assertThat(state.getVersion()).isEqualTo(1);
            assertThat(systemEvents(camera)).isEmpty();

            // Staleness tracking keeps running underneath the override.
            assertThat(state.getLastFrameAt()).isNotNull();
            Integer ocrCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM machine_state_events WHERE machine_id = ? AND source = 'ocr'",
                    Integer.class, camera.machineId());
            assertThat(ocrCount).isEqualTo(4);
        }
    }

    /**
     * P2-002: the stage-prefixed OCR countdown convention (W:mm / R:mm /
     * S:mm, see {@code OcrReading.STAGE_PATTERN}) is inferred end-to-end
     * through the same ingest -> decide -> commit path as AC1, and a machine
     * whose OCR text never uses the convention keeps the pre-P2-002 null
     * stage -- no crash, no wrong default.
     */
    @Nested
    class Ac7WashStageInferredFromOcrTextConvention {

        @Test
        void infersWashStageFromTheWPrefixConvention() throws Exception {
            TestCamera camera = seedCamera("stage-wash");

            postFrame(camera, "W:28");
            postFrame(camera, "W:25");
            postFrame(camera, "W:22");

            MachineCurrentState state = currentState(camera);
            assertThat(state.getState()).isEqualTo(MachineState.in_use);
            assertThat(state.getRemainingMin()).isEqualTo(22);
            assertThat(state.getWashStage()).isEqualTo(WashStage.wash);
        }

        @Test
        void infersRinseStageFromTheRPrefixConvention() throws Exception {
            TestCamera camera = seedCamera("stage-rinse");

            postFrame(camera, "R:15");
            postFrame(camera, "R:12");
            postFrame(camera, "R:9");

            MachineCurrentState state = currentState(camera);
            assertThat(state.getState()).isEqualTo(MachineState.in_use);
            assertThat(state.getRemainingMin()).isEqualTo(9);
            assertThat(state.getWashStage()).isEqualTo(WashStage.rinse);
        }

        @Test
        void infersSpinStageFromTheSPrefixConvention() throws Exception {
            TestCamera camera = seedCamera("stage-spin");

            postFrame(camera, "S:6");
            postFrame(camera, "S:4");
            postFrame(camera, "S:2");

            MachineCurrentState state = currentState(camera);
            assertThat(state.getState()).isEqualTo(MachineState.in_use);
            assertThat(state.getRemainingMin()).isEqualTo(2);
            assertThat(state.getWashStage()).isEqualTo(WashStage.spin);
        }

        @Test
        void keepsTheStageNullForPlainCountdownTextThatDoesNotUseTheConvention() throws Exception {
            TestCamera camera = seedCamera("stage-none");

            postFrame(camera, "28");
            postFrame(camera, "25");
            postFrame(camera, "22");

            MachineCurrentState state = currentState(camera);
            assertThat(state.getState()).isEqualTo(MachineState.in_use);
            assertThat(state.getRemainingMin()).isEqualTo(22); // unaffected by P2-002
            assertThat(state.getWashStage()).isNull();
        }

        @Test
        void tracksAStageTransitionFromWashThroughRinseToSpin() throws Exception {
            // 5 frames total: right at the per-camera ingest burst allowance
            // (OcrIngestService.BUCKET_CAPACITY), so this stays a single burst.
            TestCamera camera = seedCamera("stage-transition");

            // Consensus (AC1) is a 3-frame window of never-rising remainingMin;
            // kept dropping across the wash -> rinse -> spin transition below so
            // each new stage's reading still clears that filter.
            postFrame(camera, "W:20");
            postFrame(camera, "W:16");
            postFrame(camera, "W:12"); // consensus reached -> in_use / wash

            assertThat(currentState(camera).getWashStage()).isEqualTo(WashStage.wash);

            postFrame(camera, "R:9");
            assertThat(currentState(camera).getWashStage()).isEqualTo(WashStage.rinse);
            assertThat(currentState(camera).getRemainingMin()).isEqualTo(9);

            postFrame(camera, "S:5");
            assertThat(currentState(camera).getWashStage()).isEqualTo(WashStage.spin);
            assertThat(currentState(camera).getRemainingMin()).isEqualTo(5);
        }

        @Test
        void clearsTheStageOnceTheCycleEndsOnADarkDisplay() throws Exception {
            TestCamera camera = seedCamera("stage-done");

            postFrame(camera, "W:20");
            postFrame(camera, "W:16");
            postFrame(camera, "W:12"); // consensus reached -> in_use / wash
            postFrame(camera, ""); // dark display: cycle just ended -> done

            MachineCurrentState done = currentState(camera);
            assertThat(done.getState()).isEqualTo(MachineState.done);
            assertThat(done.getWashStage()).isNull();
        }
    }

    // ---------------------------------------------------------------- helpers

    private Instant nextCapturedAt() {
        clock = clock.plusSeconds(1);
        return clock;
    }

    private void postFrame(TestCamera camera, String rawText) throws Exception {
        postFrame(camera, rawText, 0.95);
    }

    private void postFrame(TestCamera camera, String rawText, double confidence) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", camera.deviceId());
        payload.put("captured_at", nextCapturedAt().toString());
        payload.put("confidence", confidence);
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

    private List<SystemEvent> systemEvents(TestCamera camera) {
        return jdbc.query(
                "SELECT state, remaining_min, error_code, version FROM machine_state_events "
                        + "WHERE machine_id = ? AND source = 'system' ORDER BY version ASC",
                (rs, i) -> new SystemEvent(rs.getString("state"), (Integer) rs.getObject("remaining_min"),
                        rs.getString("error_code"), rs.getInt("version")),
                camera.machineId());
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

    record TestCamera(String deviceId, String apiKey, String machineId) {
    }

    record SystemEvent(String state, Integer remainingMin, String errorCode, int version) {
    }
}
