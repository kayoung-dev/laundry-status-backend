package dev.washon.machine.payload.response;

/** Response body for POST /ingest/ocr, matching the original NestJS shape. */
public record OcrIngestResponse(String status, boolean duplicate, String eventId) {
}
