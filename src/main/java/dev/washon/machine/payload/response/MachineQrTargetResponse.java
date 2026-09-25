package dev.washon.machine.payload.response;

/**
 * Response body for GET /machines/{machineId}/qr-target (P2-001). {@code url}
 * is the deep-link a per-machine QR sticker should encode/point at: the
 * existing student room view (frontend/app/room/[roomId]/page.tsx) with a
 * {@code machine} query param so that machine's card is highlighted, e.g.
 * {@code https://app.example.com/room/room_abc?machine=machine_xyz}.
 */
public record MachineQrTargetResponse(String machineId, String roomId, String url) {
}
