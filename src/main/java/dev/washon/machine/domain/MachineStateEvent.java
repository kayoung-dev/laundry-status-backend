package dev.washon.machine.domain;

import dev.washon.common.jpa.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.FractionalSeconds;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/**
 * Append-only event log. Every OCR ingestion, admin override, and system
 * transition is recorded here and never mutated (hence no updated_at).
 *
 * <p>{@code version} is maintained manually by the state machine -- it is
 * deliberately NOT a JPA {@code @Version} column.
 */
@Entity
@Table(
        name = "machine_state_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_machine_state_events_machine_frame_hash",
                columnNames = {"machine_id", "frame_hash"}),
        indexes = @Index(
                name = "idx_machine_state_events_machine_created",
                columnList = "machine_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class MachineStateEvent {

    @Id
    @CuidId
    @Column(length = 64)
    private String id;

    @Column(name = "machine_id", nullable = false, length = 64)
    private String machineId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Machine machine;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private EventSource source;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private MachineState state;

    @Column(name = "remaining_min")
    private Integer remainingMin;

    @Column(name = "error_code")
    private String errorCode;

    private Double confidence;

    @Column(name = "raw_text", columnDefinition = "text")
    private String rawText;

    /** Idempotency key: unique per machine (NULLs never collide). */
    @Column(name = "frame_hash")
    private String frameHash;

    @FractionalSeconds(3)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private int version = 1;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
