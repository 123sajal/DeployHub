package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.EmailCode;
import com.deployhub.platform.models.enums.EmailCodePurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EmailCodeRepository extends JpaRepository<EmailCode, Long> {

    /** The newest code; sending a code removes older ones for the same email and purpose. */
    Optional<EmailCode> findFirstByEmailAndPurposeOrderByIdDesc(String email, EmailCodePurpose purpose);

    @Modifying
    @Query("DELETE FROM EmailCode c WHERE c.email = :email AND c.purpose = :purpose")
    int deleteByEmailAndPurpose(@Param("email") String email, @Param("purpose") EmailCodePurpose purpose);

    /** Counts one guess atomically; 0 rows = used, expired or out of attempts (as for password reset codes). */
    @Modifying
    @Query("""
            UPDATE EmailCode c SET c.attempts = c.attempts + 1
            WHERE c.id = :id AND c.usedAt IS NULL AND c.expiresAt > :now AND c.attempts < :maxAttempts""")
    int countAttempt(@Param("id") Long id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("UPDATE EmailCode c SET c.usedAt = :now WHERE c.id = :id AND c.usedAt IS NULL")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
