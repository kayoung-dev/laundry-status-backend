package dev.washon.machine.api;

import dev.washon.machine.application.OcrIngestService;
import dev.washon.machine.payload.request.OcrIngestRequest;
import dev.washon.machine.payload.response.OcrIngestResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ported from apps/api/src/ingest/ingest.controller.ts. Authentication, rate
 * limiting and idempotency all live in {@link OcrIngestService}.
 */
@RestController
@RequestMapping("/ingest")
public class OcrIngestApi {

    private final OcrIngestService ocrIngestService;

    public OcrIngestApi(OcrIngestService ocrIngestService) {
        this.ocrIngestService = ocrIngestService;
    }

    @PostMapping("/ocr")
    public OcrIngestResponse ingestOcr(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody OcrIngestRequest request) {
        return ocrIngestService.ingest(request.deviceId(), apiKey, request);
    }
}
