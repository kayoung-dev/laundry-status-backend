package dev.washon.auth.application;

import dev.washon.auth.domain.SignupCode;
import dev.washon.auth.domain.StudentUser;
import dev.washon.auth.infrastructure.SignupCodeRepository;
import dev.washon.auth.infrastructure.StudentUserRepository;
import dev.washon.auth.payload.request.SignupCodeRequest;
import dev.washon.auth.payload.request.SignupCodeVerifyRequest;
import dev.washon.auth.payload.request.SignupRequest;
import dev.washon.auth.payload.response.SignupResponse;
import dev.washon.common.crypto.ScryptUtil;
import dev.washon.common.redis.RateLimiterService;
import dev.washon.facility.infrastructure.RoomRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Student sign-up: {@link #sendCode} emails a 6-digit code to an address on
 * an allowed domain, {@link #verifyCode} lets the form check it right away
 * (without using it up), and {@link #signup} checks it again and creates the
 * account.
 *
 * <p>Status codes the frontend tells apart: 400 invalid input or a domain
 * that may not sign up, 409 email already registered, 422 wrong / expired /
 * missing code, 429 a new code asked for too soon, 503 the email could not
 * be sent.
 */
@Service
public class SignupService {

    static final int CODE_LENGTH = 6;
    static final int PASSWORD_MIN_LENGTH = 8;
    static final int PASSWORD_MAX_LENGTH = 128;
    static final int NAME_MAX_LENGTH = 100;
    static final int UNIT_MAX_LENGTH = 16;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@([^@\\s]+\\.[^@\\s]+)$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StudentUserRepository users;
    private final SignupCodeRepository codes;
    private final RoomRepository rooms;
    private final RateLimiterService rateLimiter;
    private final SignupCodeMailer mailer;
    private final SignupProperties properties;

    public SignupService(StudentUserRepository users, SignupCodeRepository codes, RoomRepository rooms,
            RateLimiterService rateLimiter, SignupCodeMailer mailer, SignupProperties properties) {
        this.users = users;
        this.codes = codes;
        this.rooms = rooms;
        this.rateLimiter = rateLimiter;
        this.mailer = mailer;
        this.properties = properties;
    }

    public void sendCode(SignupCodeRequest request) {
        String email = allowedEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }
        if (!rateLimiter.tryConsume("signup-code:" + email, 1, properties.resendInterval())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "A code was sent recently; try again later");
        }

        String code = newCode();
        SignupCode row = codes.findById(email).orElseGet(SignupCode::new);
        row.setEmail(email);
        row.setCodeHash(ScryptUtil.hashSecret(code));
        row.setExpiresAt(Instant.now().plus(properties.codeTtl()));
        row.setFailedAttempts(0);
        row.setCreatedAt(Instant.now());
        codes.save(row);

        try {
            mailer.send(email, code);
        } catch (MailException e) {
            codes.deleteById(email);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not send the code email", e);
        }
    }

    /** 204 when {@code code} is the live code for the address; a wrong one counts toward the attempt limit. */
    public void verifyCode(SignupCodeVerifyRequest request) {
        checkCode(allowedEmail(request.email()), request.code());
    }

    public SignupResponse signup(SignupRequest request) {
        String email = allowedEmail(request.email());
        String password = request.password();
        if (password == null || password.length() < PASSWORD_MIN_LENGTH || password.length() > PASSWORD_MAX_LENGTH
                || !password.chars().anyMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
                || !password.chars().anyMatch(Character::isDigit)) {
            throw badRequest("password must be 8-128 characters with a letter and a digit");
        }
        String name = required(request.name(), "name");
        String hall = required(request.hall(), "hall");
        String floor = optionalDigits(request.floor(), "floor");
        String roomNumber = optionalDigits(request.roomNumber(), "roomNumber");
        SignupRequest.Agreements agreements = request.agreements();
        if (agreements == null || !agreements.tos() || !agreements.privacy()) {
            throw badRequest("the terms of service and privacy policy must be agreed to");
        }
        String roomId = request.defaultRoomId();
        if (!StringUtils.hasText(roomId) || !rooms.existsById(roomId)) {
            throw badRequest("defaultRoomId must be an existing room");
        }
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        checkCode(email, request.code());

        StudentUser user = new StudentUser();
        user.setEmail(email);
        user.setPasswordHash(ScryptUtil.hashSecret(password));
        user.setName(name);
        user.setHall(hall);
        user.setFloor(floor);
        user.setRoomNumber(roomNumber);
        user.setDefaultRoomId(roomId);
        user.setPushAgreed(agreements.push());
        user.setTermsAgreedAt(Instant.now());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two sign-ups for the same address raced past existsByEmail.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered", e);
        }
        codes.deleteById(email);
        return new SignupResponse(user.getId(), user.getEmail(), user.getName(), user.getDefaultRoomId());
    }

    /**
     * 422 unless {@code code} matches the live code for {@code email}. A
     * wrong guess is counted (committed on its own, not rolled back with the
     * failed request); the code is dropped after too many.
     */
    private void checkCode(String email, String code) {
        SignupCode row = codes.findById(email).orElse(null);
        if (row == null || row.getExpiresAt().isBefore(Instant.now())) {
            throw invalidCode();
        }
        if (code == null || !code.matches("\\d{" + CODE_LENGTH + "}")
                || !ScryptUtil.verifySecret(code, row.getCodeHash())) {
            row.setFailedAttempts(row.getFailedAttempts() + 1);
            if (row.getFailedAttempts() >= properties.maxCodeAttempts()) {
                codes.delete(row);
            } else {
                codes.save(row);
            }
            throw invalidCode();
        }
    }

    /** Trimmed, lower-cased address whose domain may sign up; 400 otherwise. */
    String allowedEmail(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        var match = EMAIL.matcher(email);
        if (email.length() > 255 || !match.matches()) {
            throw badRequest("email must be a valid address");
        }
        String domain = match.group(1);
        boolean allowed = properties.allowedEmailDomains().stream()
                .anyMatch(d -> d.trim().equalsIgnoreCase(domain));
        if (!allowed) {
            throw badRequest("email domain may not sign up: " + domain);
        }
        return email;
    }

    private static String newCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        return String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt(bound));
    }

    private static String required(String value, String field) {
        if (!StringUtils.hasText(value) || value.trim().length() > NAME_MAX_LENGTH) {
            throw badRequest(field + " must be a non-empty string of at most " + NAME_MAX_LENGTH + " characters");
        }
        return value.trim();
    }

    private static String optionalDigits(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > UNIT_MAX_LENGTH || !DIGITS.matcher(trimmed).matches()) {
            throw badRequest(field + " must be digits");
        }
        return trimmed;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Sign-up code is wrong or expired");
    }
}
