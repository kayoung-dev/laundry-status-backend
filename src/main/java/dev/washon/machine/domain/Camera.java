package dev.washon.machine.domain;

import dev.washon.common.jpa.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.FractionalSeconds;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 1:1 with {@link Machine}. {@code status} tracks camera connectivity
 * (online|offline), distinct from the machine's own operational state.
 */
@Entity
@Table(name = "cameras")
@Getter
@Setter
@NoArgsConstructor
public class Camera {

    @Id
    @CuidId
    @Column(length = 64)
    private String id;

    @Column(name = "machine_id", nullable = false, unique = true, length = 64)
    private String machineId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Machine machine;

    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;

    /** {@code <salt>:<hash>} produced by ScryptUtil; the plaintext key is never stored. */
    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    @Column(nullable = false, length = 32)
    private String status = "offline";

    @FractionalSeconds(3)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @FractionalSeconds(3)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
