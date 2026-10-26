package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.enums.DeploymentStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {

    interface StatusCount {
        DeploymentStatus getStatus();

        long getTotal();
    }

    /** When a deployment was requested and when a worker first picked it up. */
    interface QueueWait {
        Instant getCreatedAt();

        Instant getStartedAt();
    }

    /** Just the status, without loading the entity (open log streams check it twice a second). */
    @Query("select d.status from Deployment d where d.id = :id")
    Optional<DeploymentStatus> findStatusById(@Param("id") Long id);

    @Query("""
            select d.createdAt as createdAt, d.startedAt as startedAt from Deployment d
            where d.project.owner.id = :ownerId and d.startedAt >= :since
            order by d.startedAt desc
            """)
    List<QueueWait> findQueueWaits(@Param("ownerId") Long ownerId, @Param("since") Instant since, Limit limit);

    @Query("""
            select d.status as status, count(d) as total from Deployment d
            where d.project.owner.id = :ownerId and d.finishedAt >= :since
            group by d.status
            """)
    List<StatusCount> countFinishedSince(@Param("ownerId") Long ownerId, @Param("since") Instant since);

    @Query("select d from Deployment d join fetch d.project p where d.id = :id and p.owner.id = :ownerId")
    Optional<Deployment> findOwned(@Param("id") Long id, @Param("ownerId") Long ownerId);

    @Query(value = """
            select d from Deployment d join fetch d.project p
            where p.owner.id = :ownerId
              and (:projectId is null or p.id = :projectId)
              and (:status is null or d.status = :status)
            """,
            countQuery = """
            select count(d) from Deployment d
            where d.project.owner.id = :ownerId
              and (:projectId is null or d.project.id = :projectId)
              and (:status is null or d.status = :status)
            """)
    Page<Deployment> search(@Param("ownerId") Long ownerId,
                            @Param("projectId") Long projectId,
                            @Param("status") DeploymentStatus status,
                            Pageable pageable);

    @Query("select d.status as status, count(d) as total from Deployment d where d.project.owner.id = :ownerId group by d.status")
    List<StatusCount> countByStatus(@Param("ownerId") Long ownerId);

    @Query("select count(d) from Deployment d where d.project.owner.id = :ownerId and d.status in :statuses")
    long countActiveForOwner(@Param("ownerId") Long ownerId, @Param("statuses") Collection<DeploymentStatus> statuses);

    List<Deployment> findByProjectIdAndStatusAndIdNot(Long projectId, DeploymentStatus status, Long id);

    Optional<Deployment> findFirstByProjectIdAndStatus(Long projectId, DeploymentStatus status);

    /** Images of a project's most recent successful deployments (kept for instant rollback). */
    @Query("""
            select d.imageRef from Deployment d
            where d.project.id = :projectId and d.imageRef is not null and d.status in :statuses
            order by d.id desc
            """)
    List<String> findRecentImageRefs(@Param("projectId") Long projectId,
                                     @Param("statuses") Collection<DeploymentStatus> statuses, Limit limit);

    /** Containers still running for a project (its LIVE deployment), e.g. to stop them when it is deleted. */
    @Query("""
            select d.containerName from Deployment d
            where d.project.id = :projectId and d.containerName is not null
              and d.status = com.deployhub.platform.models.enums.DeploymentStatus.LIVE
            """)
    List<String> findLiveContainerNames(@Param("projectId") Long projectId);

    boolean existsByProjectIdAndStatusIn(Long projectId, Collection<DeploymentStatus> statuses);

    @Modifying
    @Query("delete from Deployment d where d.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
