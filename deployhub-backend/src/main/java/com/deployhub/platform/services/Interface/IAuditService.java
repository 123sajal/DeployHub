package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.enums.AuditEventType;

public interface IAuditService {

    /** Never pass passwords, cookies or tokens in {@code details}. */
    void record(AuditEventType type, Long userId, String subject, String ipAddress, String details);
}
