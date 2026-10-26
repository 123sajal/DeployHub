package com.deployhub.platform.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One environment variable of a project. Only the encrypted value is stored. */
@Entity
@Table(name = "project_env_vars")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectEnvVar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 100)
    private String name;

    /** "v1:" + base64(iv + AES-GCM ciphertext), see SecretCipher. */
    @Column(name = "value_encrypted", nullable = false, length = 4000)
    private String valueEncrypted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ProjectEnvVar(Long projectId, String name, String valueEncrypted, Instant now) {
        this.projectId = projectId;
        this.name = name;
        this.valueEncrypted = valueEncrypted;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void replaceValue(String valueEncrypted, Instant now) {
        this.valueEncrypted = valueEncrypted;
        this.updatedAt = now;
    }
}
