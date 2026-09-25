package dev.washon;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
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
        SpringApplication.run(WashonApplication.class, args);
    }
}
