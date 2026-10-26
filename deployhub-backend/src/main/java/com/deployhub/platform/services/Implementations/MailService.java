package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.services.Interface.IMailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Sends email through SMTP (Gmail by default, see application.properties). Without an SMTP login
 * (MAIL_USERNAME unset) nothing is sent and the code is written to the log, for local development.
 */
@Slf4j
@Service
public class MailService implements IMailService {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String username;
    private final String from;

    public MailService(ObjectProvider<JavaMailSender> mailSender,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${deployhub.mail.from:}") String from) {
        this.mailSender = mailSender;
        this.username = username;
        this.from = from.isBlank() ? username : from;
    }

    @Override
    public void sendPasswordResetCode(String email, String displayName, String code, Duration validFor) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (username.isBlank() || sender == null) {
            log.warn("Email is not set up (MAIL_USERNAME is empty), so no email was sent. "
                    + "Password reset code for {}: {} (development only)", email, code);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Your DeployHub password reset code: " + code);
        message.setText("""
                Hi %s,

                Someone (hopefully you) asked to reset the password of your DeployHub account.
                Your code is:

                    %s

                It works once and expires in %d minutes.
                If you didn't ask for this, ignore this email: your password stays the same.

                - DeployHub
                """.formatted(displayName, code, validFor.toMinutes()));
        try {
            sender.send(message);
            log.info("Password reset code emailed to {}", email);
        } catch (MailException e) {
            log.error("Could not email the password reset code to {}: {}", email, e.getMessage());
        }
    }
}
