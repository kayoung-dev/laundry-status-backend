package dev.washon.realtime.payload.response;

import java.util.List;

/** Wire body of the {@code snapshot} SSE event sent immediately on connect. */
public record MachineSnapshotResponse(String roomId, List<MachineSnapshotItem> machines) {
}
