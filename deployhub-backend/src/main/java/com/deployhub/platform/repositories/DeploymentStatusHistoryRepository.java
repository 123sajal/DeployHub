package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.DeploymentStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeploymentStatusHistoryRepository extends JpaRepository<DeploymentStatusChange, Long> {

    List<DeploymentStatusChange> findByDeploymentIdOrderByIdAsc(Long deploymentId);
}
