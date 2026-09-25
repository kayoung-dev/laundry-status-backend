package dev.washon.machine.payload.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * POST /ingest/ocr body reported by a camera device. Field names mirror the
 * original NestJS DTO's snake_case wire format
 * (apps/api/src/ingest/ingest-ocr.dto.ts).
 */
public record OcrIngestRequest(
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("captured_at") Instant capturedAt,
        Double confidence,
        @JsonProperty("raw_text") String rawText,
        @JsonProperty("frame_hash") String frameHash) {
}
