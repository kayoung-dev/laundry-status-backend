package dev.washon.auth.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Emails sign-up codes over SMTP ({@code spring.mail.*}). Without an SMTP
 * host Spring Boot creates no {@link JavaMailSender}; the code is then only
 * logged, so local development works without a mail server.
 */
@Component
public class SignupCodeMailer {

    private static final Logger log = LoggerFactory.getLogger(SignupCodeMailer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final SignupProperties properties;

    public SignupCodeMailer(ObjectProvider<JavaMailSender> mailSender, SignupProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** Throws {@link org.springframework.mail.MailException} when the SMTP server refuses. */
    public void send(String email, String code) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("spring.mail.host is not set; sign-up code for {} is {} (not emailed)", email, code);
            return;
        }
        long minutes = Math.max(1, properties.codeTtl().toMinutes());
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("[WashOn] 회원가입 인증 코드 " + code);
        message.setText("""
                WashOn 회원가입 인증 코드는 %s 입니다.

                가입 화면에 이 코드를 입력해 주세요. 코드는 %d분 동안 쓸 수 있어요.
                직접 요청하지 않았다면 이 메일은 무시해 주세요.
                """.formatted(code, minutes));
        sender.send(message);
    }
}
