package com.deployhub.platform.models.entities;

import com.deployhub.platform.models.enums.LogLevel;
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

/** Append-only. The identity id doubles as the pagination cursor. */
@Entity
@Table(name = "deployment_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeploymentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_level", nullable = false, length = 10)
    private LogLevel level;

    @Column(nullable = false, length = 4000)
    private String message;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public DeploymentLog(Long deploymentId, LogLevel level, String message, Instant occurredAt) {
        this.deploymentId = deploymentId;
        this.level = level;
        this.message = message;
        this.occurredAt = occurredAt;
    }
}
