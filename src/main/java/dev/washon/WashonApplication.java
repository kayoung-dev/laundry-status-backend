package dev.washon;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WashonApplication {

    public static void main(String[] args) {
        // All timestamps are Instants persisted to timezone-naive DATETIME(3)
        // columns; pinning the JVM zone keeps any LocalDateTime conversion at
        // the JDBC boundary UTC as well (hibernate.jdbc.time_zone=UTC covers
        // Hibernate itself).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ConfigurableApplicationContext context = SpringApplication.run(WashonApplication.class, args);

        // The `seed` profile has no web server but the scheduler thread pools
        // (@EnableScheduling, TransitionScheduler) would keep the JVM alive
        // after DevSeedRunner finishes; exit explicitly so seeding terminates.
        if (context.getEnvironment().matchesProfiles("seed")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
