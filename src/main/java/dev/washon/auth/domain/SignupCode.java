package dev.washon.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.FractionalSeconds;

/** The latest emailed sign-up code for one address; only its hash is kept. */
@Entity
@Table(name = "signup_codes")
@Getter
@Setter
@NoArgsConstructor
public class SignupCode {

    /** Lower-cased. */
    @Id
    @Column(nullable = false)
    private String email;

    /** {@code <salt>:<hash>} produced by ScryptUtil. */
    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @FractionalSeconds(3)
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @FractionalSeconds(3)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
