package com.deployhub.platform.models.entities;

import com.deployhub.platform.models.enums.DeploymentStatus;
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

/** Append-only audit of every deployment status transition. */
@Entity
@Table(name = "deployment_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeploymentStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private DeploymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private DeploymentStatus toStatus;

    @Column(length = 1000)
    private String reason;

    /** "user:42", "worker:host-1a2b" or "system". */
    @Column(nullable = false, length = 120)
    private String actor;

    private Integer attempt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public DeploymentStatusChange(Long deploymentId, DeploymentStatus fromStatus, DeploymentStatus toStatus,
                                  String reason, String actor, Integer attempt, Instant occurredAt) {
        this.deploymentId = deploymentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actor = actor;
        this.attempt = attempt;
        this.occurredAt = occurredAt;
    }
}
