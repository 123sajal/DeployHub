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

/** A project's database on a DeployHub-managed server. The password lives in its DB_PASSWORD env variable. */
@Entity
@Table(name = "project_databases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectDatabase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 20)
    private String engine;

    @Column(name = "database_name", nullable = false, length = 64)
    private String databaseName;

    @Column(nullable = false, length = 32)
    private String username;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ProjectDatabase(Long projectId, String engine, String databaseName, String username, Instant createdAt) {
        this.projectId = projectId;
        this.engine = engine;
        this.databaseName = databaseName;
        this.username = username;
        this.createdAt = createdAt;
    }
}
