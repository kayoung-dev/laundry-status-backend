package dev.washon.realtime.api;

import dev.washon.facility.infrastructure.RoomRepository;
import dev.washon.realtime.application.RoomStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Ported from apps/api/src/realtime/realtime.controller.ts. SSE gateway for
 * one room: a {@code snapshot} event immediately on connect (also covers
 * client reconnects, which just open a new connection), then
 * {@code machine_changed} events for subsequent commits in this room.
 */
@RestController
@RequestMapping("/rooms")
public class RoomStreamApi {

    private final RoomRepository rooms;
    private final RoomStreamService roomStreamService;

    public RoomStreamApi(RoomRepository rooms, RoomStreamService roomStreamService) {
        this.rooms = rooms;
        this.roomStreamService = roomStreamService;
    }

    @GetMapping(path = "/{roomId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String roomId) {
        if (!rooms.existsById(roomId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room " + roomId + " not found");
        }
        return roomStreamService.stream(roomId);
    }
}
