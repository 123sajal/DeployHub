package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Long> {

    /** The newest code; requesting a code removes the older ones, so there is at most one anyway. */
    Optional<PasswordResetCode> findFirstByUserIdOrderByIdDesc(Long userId);

    @Modifying
    @Query("DELETE FROM PasswordResetCode c WHERE c.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /**
     * Counts one guess, atomically: 0 rows means the code is used up, expired or out of attempts, so two
     * parallel guesses can never both get past the limit.
     */
    @Modifying
    @Query("""
            UPDATE PasswordResetCode c SET c.attempts = c.attempts + 1
            WHERE c.id = :id AND c.usedAt IS NULL AND c.expiresAt > :now AND c.attempts < :maxAttempts""")
    int countAttempt(@Param("id") Long id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    /** Marks the code used; 0 rows means a parallel request already used it. */
    @Modifying
    @Query("UPDATE PasswordResetCode c SET c.usedAt = :now WHERE c.id = :id AND c.usedAt IS NULL")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
