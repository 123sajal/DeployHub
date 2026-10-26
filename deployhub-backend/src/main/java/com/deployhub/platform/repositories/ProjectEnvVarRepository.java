package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.ProjectEnvVar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectEnvVarRepository extends JpaRepository<ProjectEnvVar, Long> {

    List<ProjectEnvVar> findByProjectIdOrderByNameAsc(Long projectId);

    Optional<ProjectEnvVar> findByProjectIdAndName(Long projectId, String name);

    long countByProjectId(Long projectId);
}
