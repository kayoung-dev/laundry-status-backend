package dev.washon.machine.application;

import dev.washon.common.crypto.ScryptUtil;
import dev.washon.common.redis.RateLimiterService;
import dev.washon.machine.domain.Camera;
import dev.washon.machine.domain.EventSource;
import dev.washon.machine.domain.MachineState;
import dev.washon.machine.domain.MachineStateEvent;
import dev.washon.machine.infrastructure.CameraRepository;
import dev.washon.machine.infrastructure.MachineStateEventRepository;
import dev.washon.machine.payload.request.OcrIngestRequest;
import dev.washon.machine.payload.response.OcrIngestResponse;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticates the reporting camera, applies the per-camera rate limit, and
 * persists an accepted OCR frame idempotently. Ported from
 * apps/api/src/ingest/{ingest.controller,ingest.service,api-key.guard,
 * rate-limit.guard}.ts.
 *
 * <p>The stored event's {@code state} is always the {@code unknown}
 * placeholder: this records what the camera reported, not what we concluded.
 * Each newly accepted (non-duplicate) frame is then handed to
 * {@link MachineStateService}, which appends inferred transitions as separate
 * {@code source=system} rows.
 */
@Service
public class OcrIngestService {

    // At most 1 request per 5 seconds per camera, burst allowance of 5.
    private static final int BUCKET_CAPACITY = 5;
    private static final Duration REFILL_INTERVAL = Duration.ofSeconds(5);

    // MariaDB's ER_DUP_ENTRY code (SQLState 23000) for a unique-constraint
    // violation -- NOT Postgres's 23505, which does not apply here.
    private static final int DUPLICATE_ENTRY_ERROR_CODE = 1062;

    private static final Logger log = LoggerFactory.getLogger(OcrIngestService.class);

    private final CameraRepository cameras;
    private final MachineStateEventRepository events;
    private final RateLimiterService rateLimiter;
    private final MachineStateService machineStates;

    public OcrIngestService(CameraRepository cameras, MachineStateEventRepository events,
            RateLimiterService rateLimiter, MachineStateService machineStates) {
        this.cameras = cameras;
        this.events = events;
        this.rateLimiter = rateLimiter;
        this.machineStates = machineStates;
    }

    public OcrIngestResponse ingest(String deviceId, String apiKey, OcrIngestRequest request) {
        Camera camera = authenticate(deviceId, apiKey);

        if (!rateLimiter.tryConsume("ingest:" + camera.getId(), BUCKET_CAPACITY, REFILL_INTERVAL)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests");
        }

        return persist(camera, request);
    }

    private Camera authenticate(String deviceId, String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing X-Api-Key header");
        }
        if (!StringUtils.hasText(deviceId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing device_id");
        }

        Camera camera = cameras.findByDeviceId(deviceId).orElse(null);
        if (camera == null || !ScryptUtil.verifySecret(apiKey, camera.getApiKeyHash())) {
            // Same message whether the device is unknown or the key is
            // wrong, so a caller cannot enumerate valid device ids.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key");
        }
        return camera;
    }

    /**
     * Persists the raw OCR frame as a MachineStateEvent, treating a
     * duplicate {@code frame_hash} for this machine as an idempotent
     * success rather than an error.
     *
     * <p>{@code saveAndFlush} forces the INSERT -- and any unique-constraint
     * violation -- to happen here and now, inside this (non-transactional)
     * method's own repository-managed transaction, rather than being
     * deferred to a later flush point where it could surface against an
     * unrelated, already-committed-or-aborted transaction. It also means the
     * raw row is committed -- and no transaction is open -- by the time the
     * state machine takes its per-machine lock.
     *
     * <p>Package-private (not private) so the state machine's concurrency
     * test can drive the real post-authentication ingestion path without the
     * per-camera rate limit capping its burst, exactly as the original test
     * called IngestService.ingestOcrEvent directly.
     */
    OcrIngestResponse persist(Camera camera, OcrIngestRequest request) {
        MachineStateEvent event = new MachineStateEvent();
        event.setMachineId(camera.getMachineId());
        event.setSource(EventSource.ocr);
        event.setState(MachineState.unknown); // placeholder; MachineStateService derives the real state
        event.setConfidence(request.confidence());
        event.setRawText(request.rawText());
        event.setFrameHash(request.frameHash());
        if (request.capturedAt() != null) {
            event.setCreatedAt(request.capturedAt());
        }

        try {
            events.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateFrameHash(e)) {
                throw e;
            }
            String existingId = events.findByMachineIdAndFrameHash(camera.getMachineId(), request.frameHash())
                    .map(MachineStateEvent::getId)
                    .orElse("");
            return new OcrIngestResponse("accepted", true, existingId);
        }

        try {
            machineStates.handleOcrFrame(camera.getMachineId(), new IngestedOcrFrame(
                    event.getId(), event.getRawText(), event.getConfidence(),
                    // DATETIME(3) keeps milliseconds; match what was stored.
                    event.getCreatedAt().truncatedTo(ChronoUnit.MILLIS)));
        } catch (RuntimeException e) {
            // The raw frame is already durable; a failed inference must not turn
            // into a 5xx that makes the camera retry a frame we would then reject
            // as a duplicate. The next frame re-derives the state.
            log.error("State machine failed for machine {}", camera.getMachineId(), e);
        }
        return new OcrIngestResponse("accepted", false, event.getId());
    }

    private boolean isDuplicateFrameHash(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause instanceof SQLIntegrityConstraintViolationException sqlEx
                && sqlEx.getErrorCode() == DUPLICATE_ENTRY_ERROR_CODE;
    }
}
