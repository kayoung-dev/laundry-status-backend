package dev.washon.facility.infrastructure;

import dev.washon.facility.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> {
}
