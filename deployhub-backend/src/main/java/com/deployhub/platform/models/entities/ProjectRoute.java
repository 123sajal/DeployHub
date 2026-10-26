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

/** Requests to {@code pathPrefix} on this project's address go to the target project's live app. */
@Entity
@Table(name = "project_routes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "path_prefix", nullable = false, length = 100)
    private String pathPrefix;

    @Column(name = "target_project_id", nullable = false)
    private Long targetProjectId;

    public ProjectRoute(Long projectId, String pathPrefix, Long targetProjectId) {
        this.projectId = projectId;
        this.pathPrefix = pathPrefix;
        this.targetProjectId = targetProjectId;
    }
}
