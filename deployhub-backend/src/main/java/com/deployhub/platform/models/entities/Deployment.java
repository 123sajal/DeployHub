package com.deployhub.platform.models.entities;

import com.deployhub.platform.deployment.DeploymentStateMachine;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One attempt to deploy a project. Status only changes through {@link #transitionTo},
 * which enforces {@link DeploymentStateMachine}. The {@code @Version} column makes two
 * concurrent writers (e.g. the worker and a cancel request) conflict instead of silently
 * overwriting each other.
 */
@Entity
@Table(name = "deployments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectMaster project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeploymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 20)
    private ExecutionMode executionMode;

    @Column(name = "commit_ref", length = 100)
    private String commitRef;

    /** Set when this deployment is a rollback: the earlier deployment whose code it redeploys. */
    @Column(name = "rollback_of_id")
    private Long rollbackOfId;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    private Integer version;

    public static Deployment queue(ProjectMaster project, String commitRef, ExecutionMode mode, Instant now) {
        return queue(project, commitRef, null, mode, now);
    }

    public static Deployment queue(ProjectMaster project, String commitRef, Long rollbackOfId, ExecutionMode mode,
                                   Instant now) {
        Deployment deployment = new Deployment();
        deployment.rollbackOfId = rollbackOfId;
        deployment.project = project;
        deployment.commitRef = commitRef;
        deployment.executionMode = mode;
        deployment.status = DeploymentStatus.QUEUED;
        deployment.createdAt = now;
        deployment.statusChangedAt = now;
        return deployment;
    }

    /**
     * Only a deployment that was live and has since been replaced can be rolled back to. The current LIVE one
     * is already running; FAILED/CANCELLED ones never went live, so there is nothing known-good to return to.
     */
    public boolean canBeRolledBackTo() {
        return status == DeploymentStatus.SUPERSEDED;
    }

    public void transitionTo(DeploymentStatus target, Instant now, String reason) {
        DeploymentStateMachine.assertTransition(status, target);
        if (status == DeploymentStatus.QUEUED && target == DeploymentStatus.BUILDING) {
            startedAt = now;
        }
        // Set once: a LIVE deployment that is later SUPERSEDED keeps the time it went live.
        if (finishedAt == null && (target.isTerminal() || target == DeploymentStatus.LIVE)) {
            finishedAt = now;
        }
        if (target == DeploymentStatus.FAILED || target == DeploymentStatus.CANCELLED) {
            failureReason = reason;
        }
        status = target;
        statusChangedAt = now;
    }
}
