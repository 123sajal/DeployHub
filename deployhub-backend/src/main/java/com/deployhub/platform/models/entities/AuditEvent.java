package com.deployhub.platform.models.entities;

import com.deployhub.platform.models.enums.AuditEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Append-only security/audit record. Never contains passwords, cookies or tokens. */
@Entity
@Table(name = "audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private AuditEventType type;

    @Column(name = "user_id")
    private Long userId;

    /** Normalised email the event is about (also for failed logins of unknown accounts). */
    @Column(length = 254)
    private String subject;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(length = 500)
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public AuditEvent(AuditEventType type, Long userId, String subject, String ipAddress, String details,
                      Instant occurredAt) {
        this.type = type;
        this.userId = userId;
        this.subject = subject;
        this.ipAddress = ipAddress;
        this.details = details;
        this.occurredAt = occurredAt;
    }
}
