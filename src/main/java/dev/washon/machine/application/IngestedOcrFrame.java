package dev.washon.machine.application;

import java.time.Instant;

/** The raw OCR frame ingestion just accepted, as stored by {@link OcrIngestService}. */
public record IngestedOcrFrame(String eventId, String rawText, Double confidence, Instant capturedAt) {
}
