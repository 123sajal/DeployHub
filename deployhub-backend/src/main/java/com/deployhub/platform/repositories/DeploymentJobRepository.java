package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.DeploymentJob;
import com.deployhub.platform.models.enums.JobStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The queue operations. Every state change of a job is a conditional UPDATE whose WHERE clause
 * re-checks the precondition, and the returned row count says whether we won:
 * <ul>
 *   <li>claim: only if still PENDING-and-due, or RUNNING with an expired lease (abandoned)</li>
 *   <li>heartbeat / finish / reschedule: only if this worker still holds the lease</li>
 * </ul>
 * Bulk updates bypass JPA's @Version, so they bump {@code version} themselves.
 */
public interface DeploymentJobRepository extends JpaRepository<DeploymentJob, Long> {

    Optional<DeploymentJob> findByDeploymentId(Long deploymentId);

    /**
     * True while a job of one of the project's deployments is still being worked on. A deployment shows LIVE a moment
     * before its job has written its last log lines and finished; deleting in that window broke the job (BUG-18).
     */
    @Query("""
            SELECT COUNT(j) > 0 FROM DeploymentJob j
            WHERE j.status = com.deployhub.platform.models.enums.JobStatus.RUNNING
              AND j.deploymentId IN (SELECT d.id FROM Deployment d WHERE d.project.id = :projectId)""")
    boolean anyRunningForProject(@Param("projectId") Long projectId);

    @Query("""
            SELECT COUNT(j) > 0 FROM DeploymentJob j
            WHERE j.status = com.deployhub.platform.models.enums.JobStatus.RUNNING AND j.deploymentId = :deploymentId""")
    boolean isRunning(@Param("deploymentId") Long deploymentId);

    @Query("""
            select j.id from DeploymentJob j
            where (j.status = com.deployhub.platform.models.enums.JobStatus.PENDING and j.availableAt <= :now)
               or (j.status = com.deployhub.platform.models.enums.JobStatus.RUNNING and j.leaseExpiresAt < :now)
            order by j.availableAt, j.id
            """)
    List<Long> findClaimableIds(@Param("now") Instant now, Limit limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeploymentJob j
            set j.status = com.deployhub.platform.models.enums.JobStatus.RUNNING, j.lockedBy = :worker,
                j.leaseExpiresAt = :leaseUntil, j.attempt = j.attempt + 1, j.updatedAt = :now, j.version = j.version + 1
            where j.id = :id
              and ((j.status = com.deployhub.platform.models.enums.JobStatus.PENDING and j.availableAt <= :now)
                or (j.status = com.deployhub.platform.models.enums.JobStatus.RUNNING and j.leaseExpiresAt < :now))
            """)
    int claim(@Param("id") Long id, @Param("worker") String worker, @Param("leaseUntil") Instant leaseUntil,
              @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeploymentJob j set j.leaseExpiresAt = :leaseUntil, j.updatedAt = :now, j.version = j.version + 1
            where j.id = :id and j.lockedBy = :worker
              and j.status = com.deployhub.platform.models.enums.JobStatus.RUNNING
            """)
    int heartbeat(@Param("id") Long id, @Param("worker") String worker, @Param("leaseUntil") Instant leaseUntil,
                  @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeploymentJob j
            set j.status = :status, j.lockedBy = null, j.leaseExpiresAt = null,
                j.lastError = coalesce(:error, j.lastError), j.updatedAt = :now, j.version = j.version + 1
            where j.id = :id and j.lockedBy = :worker
              and j.status = com.deployhub.platform.models.enums.JobStatus.RUNNING
            """)
    int finish(@Param("id") Long id, @Param("worker") String worker, @Param("status") JobStatus status,
               @Param("error") String error, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeploymentJob j
            set j.status = com.deployhub.platform.models.enums.JobStatus.PENDING, j.availableAt = :availableAt,
                j.lockedBy = null, j.leaseExpiresAt = null, j.lastError = :error, j.updatedAt = :now,
                j.version = j.version + 1
            where j.id = :id and j.lockedBy = :worker
              and j.status = com.deployhub.platform.models.enums.JobStatus.RUNNING
            """)
    int reschedule(@Param("id") Long id, @Param("worker") String worker, @Param("availableAt") Instant availableAt,
                   @Param("error") String error, @Param("now") Instant now);

    /** User cancelled: a job still waiting in the queue is dropped right away. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeploymentJob j
            set j.status = com.deployhub.platform.models.enums.JobStatus.CANCELLED, j.updatedAt = :now,
                j.version = j.version + 1
            where j.deploymentId = :deploymentId
              and j.status = com.deployhub.platform.models.enums.JobStatus.PENDING
            """)
    int cancelIfPending(@Param("deploymentId") Long deploymentId, @Param("now") Instant now);

    long countByStatus(JobStatus status);

    /** PENDING and due: waiting for a free worker. */
    long countByStatusAndAvailableAtLessThanEqual(JobStatus status, Instant now);

    /** PENDING but not due yet: a retry scheduled for later. */
    long countByStatusAndAvailableAtGreaterThan(JobStatus status, Instant now);
}
