package dev.washon.auth.domain;

import dev.washon.common.jpa.CuidId;
import dev.washon.facility.domain.Room;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

/** A student account created through POST /auth/signup. */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class StudentUser {

    @Id
    @CuidId
    @Column(length = 64)
    private String id;

    /** Lower-cased. */
    @Column(nullable = false, unique = true)
    private String email;

    /** {@code <salt>:<hash>} produced by ScryptUtil. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String hall;

    @Column(length = 16)
    private String floor;

    @Column(name = "room_number", length = 16)
    private String roomNumber;

    @Column(name = "default_room_id", length = 64)
    private String defaultRoomId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_room_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Room defaultRoom;

    @Column(name = "push_agreed", nullable = false)
    private boolean pushAgreed;

    @FractionalSeconds(3)
    @Column(name = "terms_agreed_at", nullable = false)
    private Instant termsAgreedAt;

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
