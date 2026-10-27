package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.UserMaster;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserMaster, Long> {

    Optional<UserMaster> findByEmail(String email);

    /** Locks the user's row until the transaction ends: billing decisions for one user happen one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserMaster u WHERE u.id = :id")
    Optional<UserMaster> findByIdForUpdate(@Param("id") Long id);
}
