package com.deployhub.platform.deployment;

import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.DeploymentStatusChange;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.repositories.DeploymentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * The only way a deployment's status changes: validates the move (state machine) and records it
 * in deployment_status_history in the same transaction, so the history can never disagree with the status.
 */
@Component
@RequiredArgsConstructor
public class DeploymentTransitionService {

    private final DeploymentStatusHistoryRepository history;
    private final Clock clock;

    public void recordCreated(Deployment deployment, String reason, String actor) {
        history.save(new DeploymentStatusChange(deployment.getId(), null, deployment.getStatus(), reason,
                actor, null, deployment.getCreatedAt()));
    }

    public void transition(Deployment deployment, DeploymentStatus target, String reason, String actor, Integer attempt) {
        DeploymentStatus from = deployment.getStatus();
        Instant now = clock.instant();
        deployment.transitionTo(target, now, reason);
        history.save(new DeploymentStatusChange(deployment.getId(), from, target, reason, actor, attempt, now));
    }
}
