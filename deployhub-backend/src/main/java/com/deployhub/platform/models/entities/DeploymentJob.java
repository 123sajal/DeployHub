package com.deployhub.platform.models.entities;

import com.deployhub.platform.models.enums.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A row in the durable deployment queue. Claiming, heartbeats and completion are done with
 * conditional UPDATE statements in {@code DeploymentJobRepository}, so two workers can never both
 * believe they own the same job.
 */
@Entity
@Table(name = "deployment_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeploymentJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false, unique = true)
    private Long deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(nullable = false)
    private Integer attempt;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Integer version;

    public static DeploymentJob enqueue(Long deploymentId, int maxAttempts, Instant now) {
        DeploymentJob job = new DeploymentJob();
        job.deploymentId = deploymentId;
        job.status = JobStatus.PENDING;
        job.attempt = 0;
        job.maxAttempts = maxAttempts;
        job.availableAt = now;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }
}
