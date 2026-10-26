package com.deployhub.platform.security;

import com.deployhub.platform.config.DeployHubProperties;
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
    private final Clock clock;

    public LoginThrottleService(AuditEventRepository auditEvents, IAuditService auditService,
                                DeployHubProperties properties, Clock clock) {
        this.auditEvents = auditEvents;
        this.auditService = auditService;
        this.settings = properties.security();
        this.clock = clock;
    }

    public void checkLogin(String email, String ip) {
        Instant since = clock.instant().minus(settings.loginWindow());
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
}
