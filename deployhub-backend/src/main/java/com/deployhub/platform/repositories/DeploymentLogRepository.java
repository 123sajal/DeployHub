package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.DeploymentLog;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DeploymentLogRepository extends JpaRepository<DeploymentLog, Long> {

    /** Keyset ("cursor") pagination: uses the (deployment_id, id) index, never OFFSET. */
    List<DeploymentLog> findByDeploymentIdAndIdGreaterThanOrderByIdAsc(Long deploymentId, Long afterId, Limit limit);

    /** The newest lines first (e.g. the end of a failed build, for the assistant). */
    List<DeploymentLog> findByDeploymentIdOrderByIdDesc(Long deploymentId, Limit limit);

    @Modifying
    @Query("delete from DeploymentLog l where l.deploymentId = :deploymentId")
    void deleteByDeploymentId(@Param("deploymentId") Long deploymentId);

    @Modifying
    @Query("delete from DeploymentLog l where l.deploymentId in (select d.id from Deployment d where d.project.id = :projectId)")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
