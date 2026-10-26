package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.ProjectRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRouteRepository extends JpaRepository<ProjectRoute, Long> {

    List<ProjectRoute> findByProjectIdOrderByPathPrefixAsc(Long projectId);

    /** Routes of other projects that send traffic to {@code targetProjectId}. */
    List<ProjectRoute> findByTargetProjectId(Long targetProjectId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProjectRoute r where r.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
