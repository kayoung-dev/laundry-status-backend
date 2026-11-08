package dev.washon.auth.infrastructure;

import dev.washon.auth.domain.StudentUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentUserRepository extends JpaRepository<StudentUser, String> {

    boolean existsByEmail(String email);
}
