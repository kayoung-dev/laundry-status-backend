package dev.washon.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.admin.payload.response.AuditEntryResponse.OverrideAuditDetail;
import dev.washon.machine.domain.EventSource;
import dev.washon.machine.domain.MachineStateEvent;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What an admin did, stored on the audit row. Ported from
 * apps/api/src/admin/override-audit.ts.
 *
 * <p>{@link MachineStateEvent} already carries machineId, state, version and
 * createdAt (the timestamp), and is append-only -- it just has no column for
 * <em>who</em> acted, because every other source ({@code ocr}, {@code system})
 * is a machine. Rather than add an actor column used by one of three sources,
 * an {@code admin_override} row carries this JSON in {@code raw_text}, the
 * free-form detail column the OCR source uses for its own raw payload, so the
 * audit trail stays one queryable table:
 *
 * <pre>{@code
 * SELECT created_at, state, raw_text FROM machine_state_events
 * WHERE machine_id = ? AND source = 'admin_override'
 * ORDER BY created_at DESC;
 * }</pre>
 */
@Component
public class OverrideAuditCodec {

    static final String SET = "set";
    static final String CLEAR = "clear";
    private static final Set<String> ACTIONS = Set.of(SET, CLEAR);

    private final ObjectMapper objectMapper;

    public OverrideAuditCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(OverrideAuditDetail detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode override audit detail", e);
        }
    }

    /** Returns null for any row that is not a well-formed admin_override record. */
    public OverrideAuditDetail decode(MachineStateEvent event) {
        if (event.getSource() != EventSource.admin_override || event.getRawText() == null) {
            return null;
        }
        try {
            OverrideAuditDetail detail = objectMapper.readValue(event.getRawText(), OverrideAuditDetail.class);
            return ACTIONS.contains(detail.action()) && detail.adminEmail() != null ? detail : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
