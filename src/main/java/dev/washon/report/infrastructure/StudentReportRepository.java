package dev.washon.report.infrastructure;

import dev.washon.report.domain.StudentReport;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentReportRepository extends JpaRepository<StudentReport, String> {

    /**
     * Plan section 4.6 / SB-004 AC #2: how many distinct device_ids have
     * reported {@code machineId} since {@code windowStart}. Repeated reports
     * from the same device_id count once (COUNT DISTINCT); reports older
     * than {@code windowStart} are excluded by the WHERE clause.
     *
     * <p>Single-machine form of the bulk GROUP BY/HAVING query a future
     * admin-dashboard needs-check badge (SB-006) could run for every machine
     * at once:
     *
     * <pre>{@code
     * SELECT machine_id, COUNT(DISTINCT device_id) AS distinct_device_count
     * FROM student_reports
     * WHERE created_at >= :windowStart
     * GROUP BY machine_id
     * HAVING COUNT(DISTINCT device_id) >= 3;
     * }</pre>
     */
    @Query("SELECT COUNT(DISTINCT sr.deviceId) FROM StudentReport sr "
            + "WHERE sr.machineId = :machineId AND sr.createdAt >= :windowStart")
    long countDistinctDeviceIdsSince(@Param("machineId") String machineId, @Param("windowStart") Instant windowStart);
}
