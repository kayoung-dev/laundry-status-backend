package dev.washon.machine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.FractionalSeconds;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Materialized current state, one row per machine (PK = machine_id), kept in
 * sync from {@link MachineStateEvent} by the state machine.
 *
 * <p>{@code version} is maintained manually by the state machine
 * ({@code UPDATE ... WHERE version = :old}) -- it is deliberately NOT a JPA
 * {@code @Version} column.
 */
@Entity
@Table(name = "machine_current_states")
@Getter
@Setter
@NoArgsConstructor
public class MachineCurrentState {

    @Id
    @Column(name = "machine_id", length = 64)
    private String machineId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Machine machine;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private MachineState state = MachineState.unknown;

    @Column(name = "remaining_min")
    private Integer remainingMin;

    /** P2-002: which part of the wash cycle is running (null when not in_use, or unknown -- see OcrReading). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "wash_stage", length = 16)
    private WashStage washStage;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "override_active", nullable = false)
    private boolean overrideActive = false;

    @Column(name = "override_by")
    private String overrideBy;

    @UpdateTimestamp
    @FractionalSeconds(3)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private int version = 1;

    /** Capture time of the most recent accepted OCR frame (camera-down sweep reads it). */
    @FractionalSeconds(3)
    @Column(name = "last_frame_at")
    private Instant lastFrameAt;

    /** Last frame time the machine went stale from; cleared when frames resume. */
    @FractionalSeconds(3)
    @Column(name = "stale_since")
    private Instant staleSince;
}
