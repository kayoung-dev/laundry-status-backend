package dev.washon.machine.payload.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * MQTT payload a camera publishes to {@code washon/devices/{device_id}/ocr}:
 * the POST /ingest/ocr body plus {@code api_key}, which MQTT has no header
 * for. {@code device_id} is optional here -- the topic is authoritative.
 */
public record OcrMqttMessage(
        @JsonProperty("api_key") String apiKey,
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("captured_at") Instant capturedAt,
        Double confidence,
        @JsonProperty("raw_text") String rawText,
        @JsonProperty("frame_hash") String frameHash) {

    public OcrIngestRequest toIngestRequest(String topicDeviceId) {
        return new OcrIngestRequest(topicDeviceId, capturedAt, confidence, rawText, frameHash);
    }
}
