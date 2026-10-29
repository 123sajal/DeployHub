package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.AssistantUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AssistantUsageRepository extends JpaRepository<AssistantUsage, Long> {

    Optional<AssistantUsage> findByUsageDayAndSubject(String usageDay, String subject);

    /** Everyone's AI spending on that day, in thousandths of a paisa. */
    @Query("SELECT COALESCE(SUM(u.costMillipaise), 0) FROM AssistantUsage u WHERE u.usageDay = :day")
    long totalCostOn(@Param("day") String day);

    /** Adds one request atomically; 0 rows = no row for that day and subject yet. */
    @Modifying
    @Query("""
            UPDATE AssistantUsage u SET u.requests = u.requests + 1, u.inputTokens = u.inputTokens + :input,
                u.outputTokens = u.outputTokens + :output, u.costMillipaise = u.costMillipaise + :cost
            WHERE u.usageDay = :day AND u.subject = :subject""")
    int add(@Param("day") String day, @Param("subject") String subject, @Param("input") long input,
            @Param("output") long output, @Param("cost") long cost);
}
