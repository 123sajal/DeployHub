package com.deployhub.platform.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.time.Instant;

/**
 * Remembers which deployment an Idempotency-Key created, per user.
 *
 * <p>Implements {@link Persistable} with {@code isNew() == true} so that {@code save()} always
 * INSERTs. With a client-chosen id, Spring Data would otherwise use merge(), an "upsert": a request
 * racing with an identical one could then overwrite the winner's row instead of being rejected by
 * the primary key, and create a duplicate deployment.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord implements Persistable<IdempotencyRecord.Key> {

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {

        @Column(name = "user_id", nullable = false)
        private Long userId;

        @Column(name = "idem_key", nullable = false, length = 100)
        private String value;

        public Key(Long userId, String value) {
            this.userId = userId;
            this.value = value;
        }
    }

    @EmbeddedId
    private Key id;

    /** SHA-256 of what the request asked for, to detect a key reused for a different request. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean newRecord = true;

    public IdempotencyRecord(Key id, String requestHash, Long deploymentId, Instant createdAt) {
        this.id = id;
        this.requestHash = requestHash;
        this.deploymentId = deploymentId;
        this.createdAt = createdAt;
    }

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newRecord = false;
    }
}
