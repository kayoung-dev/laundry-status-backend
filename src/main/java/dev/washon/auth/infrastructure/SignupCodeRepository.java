package dev.washon.auth.infrastructure;

import dev.washon.auth.domain.SignupCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignupCodeRepository extends JpaRepository<SignupCode, String> {
}
