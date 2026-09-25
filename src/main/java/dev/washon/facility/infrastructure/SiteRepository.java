package dev.washon.facility.infrastructure;

import dev.washon.facility.domain.Site;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteRepository extends JpaRepository<Site, String> {
}
