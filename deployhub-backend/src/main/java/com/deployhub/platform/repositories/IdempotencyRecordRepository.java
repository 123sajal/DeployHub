package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Key> {
}
