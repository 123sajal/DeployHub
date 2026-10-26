package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.AuditEvent;
import com.deployhub.platform.models.enums.AuditEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    long countByTypeAndSubjectAndIpAddressAndOccurredAtAfter(AuditEventType type, String subject, String ipAddress,
                                                             Instant after);

    long countByTypeAndIpAddressAndOccurredAtAfter(AuditEventType type, String ipAddress, Instant after);

    List<AuditEvent> findByUserIdOrderByIdAsc(Long userId);
}
