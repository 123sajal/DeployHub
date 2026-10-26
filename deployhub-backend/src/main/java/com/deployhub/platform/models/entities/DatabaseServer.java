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

/** A database server DeployHub runs (one per engine) and the encrypted password it administers it with. */
@Entity
@Table(name = "database_servers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DatabaseServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String engine;

    @Column(name = "admin_password_encrypted", nullable = false, length = 4000)
    private String adminPasswordEncrypted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public DatabaseServer(String engine, String adminPasswordEncrypted, Instant createdAt) {
        this.engine = engine;
        this.adminPasswordEncrypted = adminPasswordEncrypted;
        this.createdAt = createdAt;
    }
}
