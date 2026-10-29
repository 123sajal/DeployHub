package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.DeploymentExplanation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentExplanationRepository extends JpaRepository<DeploymentExplanation, Long> {
}
