package dev.washon.machine.payload.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * MQTT payload a camera publishes to {@code {prefix}/{device_id}/ocr}: the
 * POST /ingest/ocr body. {@code device_id} is optional here -- the topic is
 * authoritative -- and there is no API key: the broker authenticates each
 * camera with its own account.
 */
public record OcrMqttMessage(
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("captured_at") Instant capturedAt,
        Double confidence,
        @JsonProperty("raw_text") String rawText,
        @JsonProperty("frame_hash") String frameHash) {

    public OcrIngestRequest toIngestRequest(String topicDeviceId) {
        return new OcrIngestRequest(topicDeviceId, capturedAt, confidence, rawText, frameHash);
    }
}
