package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.AuditEvent;
import com.deployhub.platform.models.enums.AuditEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    long countByTypeAndSubjectAndIpAddressAndOccurredAtAfter(AuditEventType type, String subject, String ipAddress,
                                                             Instant after);

    long countByTypeAndSubjectAndOccurredAtAfter(AuditEventType type, String subject, Instant after);

    long countByTypeAndIpAddressAndOccurredAtAfter(AuditEventType type, String ipAddress, Instant after);

    /** E.g. the account's last password reset: login failures before it no longer count. */
    Optional<AuditEvent> findFirstByTypeAndSubjectOrderByOccurredAtDesc(AuditEventType type, String subject);

    List<AuditEvent> findByUserIdOrderByIdAsc(Long userId);
}
