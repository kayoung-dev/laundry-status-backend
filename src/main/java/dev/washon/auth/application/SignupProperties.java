package dev.washon.auth.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Student sign-up settings ({@code washon.signup.*}).
 *
 * @param allowedEmailDomains domains (the part after {@code @}) that may sign up, case-insensitive
 * @param codeTtl             how long an emailed code stays valid; the frontend counts down 5 minutes
 * @param resendInterval      minimum gap between two codes sent to the same address
 * @param maxCodeAttempts     wrong codes tolerated before the code is thrown away
 * @param mailFrom            From header of the code email
 */
@ConfigurationProperties("washon.signup")
public record SignupProperties(
        @DefaultValue({"gmail.com", "handong.ac.kr"}) List<String> allowedEmailDomains,
        @DefaultValue("5m") Duration codeTtl,
        @DefaultValue("60s") Duration resendInterval,
        @DefaultValue("5") int maxCodeAttempts,
        @DefaultValue("WashOn <no-reply@washon.site>") String mailFrom) {
}
