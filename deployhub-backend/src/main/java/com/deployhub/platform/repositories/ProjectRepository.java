package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.ApplicationType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Every read is scoped by owner id so one user can never load another user's project. */
public interface ProjectRepository extends JpaRepository<ProjectMaster, Long> {

    Optional<ProjectMaster> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByOwnerIdAndName(Long ownerId, String name);

    boolean existsByOwnerIdAndNameAndIdNot(Long ownerId, String name, Long id);

    long countByOwnerId(Long ownerId);

    /**
     * SELECT ... FOR UPDATE on the project row. Held until the transaction ends, so only one
     * deployment of a project can be switching to LIVE at a time, across all workers.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectMaster p where p.id = :id")
    Optional<ProjectMaster> lockById(@Param("id") Long id);

    @Query("""
            select p from ProjectMaster p
            where p.owner.id = :ownerId
              and (lower(p.name) like :namePattern escape '\\' or lower(p.repositoryUrl) like :namePattern escape '\\')
              and (:appType is null or p.appType = :appType)
            """)
    Page<ProjectMaster> search(@Param("ownerId") Long ownerId,
                               @Param("namePattern") String namePattern,
                               @Param("appType") ApplicationType appType,
                               Pageable pageable);
}
