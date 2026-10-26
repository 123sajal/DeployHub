package com.deployhub.platform.security;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.config.PasswordResetProperties;
import com.deployhub.platform.models.entities.AuditEvent;
import com.deployhub.platform.exceptions.TooManyRequestsException;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.AuditEventRepository;
import com.deployhub.platform.services.Interface.IAuditService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Brute-force protection backed by the audit table, so limits survive restarts and are shared
 * by every backend instance. Limits are keyed by (email, IP) and by IP - not by email alone,
 * so an attacker can't lock a victim out of their account from elsewhere.
 * Deliberately not @Transactional: the "throttled" audit row must commit even though we then throw.
 */
@Service
public class LoginThrottleService {

    private final AuditEventRepository auditEvents;
    private final IAuditService auditService;
    private final DeployHubProperties.Security settings;
    private final PasswordResetProperties reset;
    private final Clock clock;

    public LoginThrottleService(AuditEventRepository auditEvents, IAuditService auditService,
                                DeployHubProperties properties, PasswordResetProperties reset, Clock clock) {
        this.auditEvents = auditEvents;
        this.auditService = auditService;
        this.settings = properties.security();
        this.reset = reset;
        this.clock = clock;
    }

    public void checkLogin(String email, String ip) {
        Instant windowStart = clock.instant().minus(settings.loginWindow());
        // Failures before a password reset don't count: whoever just reset it can log in straight away.
        Instant since = auditEvents.findFirstByTypeAndSubjectOrderByOccurredAtDesc(AuditEventType.PASSWORD_RESET, email)
                .map(AuditEvent::getOccurredAt).filter(windowStart::isBefore).orElse(windowStart);
        long perAccount = auditEvents.countByTypeAndSubjectAndIpAddressAndOccurredAtAfter(
                AuditEventType.LOGIN_FAILED, email, ip, since);
        long perIp = auditEvents.countByTypeAndIpAddressAndOccurredAtAfter(AuditEventType.LOGIN_FAILED, ip, since);
        if (perAccount >= settings.maxFailuresPerAccount() || perIp >= settings.maxFailuresPerIp()) {
            auditService.record(AuditEventType.LOGIN_THROTTLED, null, email, ip, null);
            throw new TooManyRequestsException("Too many failed login attempts. Try again later.",
                    settings.loginWindow());
        }
    }

    public void checkRegistration(String ip) {
        Instant since = clock.instant().minus(settings.registrationWindow());
        if (auditEvents.countByTypeAndIpAddressAndOccurredAtAfter(AuditEventType.REGISTERED, ip, since)
                >= settings.maxRegistrationsPerIp()) {
            auditService.record(AuditEventType.REGISTRATION_THROTTLED, null, null, ip, null);
            throw new TooManyRequestsException("Too many accounts created from this network. Try again later.",
                    settings.registrationWindow());
        }
    }

    /** Codes requested per email (no flooding someone's inbox) and per IP (no spraying many addresses). */
    public void checkPasswordResetRequest(String email, String ip) {
        Instant since = clock.instant().minus(reset.requestWindow());
        long perEmail = auditEvents.countByTypeAndSubjectAndOccurredAtAfter(
                AuditEventType.PASSWORD_RESET_REQUESTED, email, since);
        long perIp = auditEvents.countByTypeAndIpAddressAndOccurredAtAfter(
                AuditEventType.PASSWORD_RESET_REQUESTED, ip, since);
        if (perEmail >= reset.maxRequestsPerEmail() || perIp >= reset.maxRequestsPerIp()) {
            auditService.record(AuditEventType.PASSWORD_RESET_THROTTLED, null, email, ip, null);
            throw new TooManyRequestsException("Too many reset codes requested. Try again later.",
                    reset.requestWindow());
        }
    }

    /** Wrong codes per IP. Each code also allows only a few guesses (see PasswordResetService). */
    public void checkPasswordResetAttempt(String ip) {
        Instant since = clock.instant().minus(reset.requestWindow());
        if (auditEvents.countByTypeAndIpAddressAndOccurredAtAfter(AuditEventType.PASSWORD_RESET_FAILED, ip, since)
                >= reset.maxFailuresPerIp()) {
            auditService.record(AuditEventType.PASSWORD_RESET_THROTTLED, null, null, ip, null);
            throw new TooManyRequestsException("Too many wrong codes. Try again later.", reset.requestWindow());
        }
    }
}
