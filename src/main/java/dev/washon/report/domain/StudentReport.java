package dev.washon.report.domain;

import dev.washon.common.jpa.CuidId;
import dev.washon.machine.domain.Machine;
import dev.washon.machine.domain.MachineState;
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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.FractionalSeconds;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "student_reports",
        indexes = @Index(
                name = "idx_student_reports_machine_created",
                columnList = "machine_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class StudentReport {

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
    @Column(name = "reported_state", nullable = false, length = 32)
    private MachineState reportedState;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @FractionalSeconds(3)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
