package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.ProjectDatabase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectDatabaseRepository extends JpaRepository<ProjectDatabase, Long> {

    Optional<ProjectDatabase> findByProjectId(Long projectId);
}
