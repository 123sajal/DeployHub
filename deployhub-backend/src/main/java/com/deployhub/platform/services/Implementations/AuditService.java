package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.models.entities.AuditEvent;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.AuditEventRepository;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.utils.LogSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** Joins the caller's transaction when there is one, so an audit row commits or rolls back with the action. */
@Service
@RequiredArgsConstructor
public class AuditService implements IAuditService {

    private final AuditEventRepository repository;
    private final Clock clock;

    @Override
    public void record(AuditEventType type, Long userId, String subject, String ipAddress, String details) {
        String safeDetails = details == null ? null : truncate(LogSanitizer.sanitize(details), 500);
        repository.save(new AuditEvent(type, userId, truncate(subject, 254), ipAddress, safeDetails, clock.instant()));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
