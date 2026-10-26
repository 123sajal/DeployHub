package com.deployhub.platform.models.entities;

import com.deployhub.platform.models.enums.ApplicationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class ProjectMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserMaster owner;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "repository_url", nullable = false, length = 300)
    private String repositoryUrl;

    @Column(nullable = false, length = 100)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_type", nullable = false, length = 30)
    private ApplicationType appType;

    @Column(name = "build_command", length = 500)
    private String buildCommand;

    /** Optional for Dockerfile projects (the image's own CMD is used); required for every other type. */
    @Column(name = "start_command", length = 500)
    private String startCommand;

    /** Port the app listens on inside its container; null = the app type's default (see ApplicationType). */
    @Column(name = "app_port")
    private Integer appPort;

    /** Path the health check requests; null = "/". */
    @Column(name = "health_check_path", length = 200)
    private String healthCheckPath;

    /** The project's fixed address on this machine (http://localhost:publicPort); set on its first real go-live. */
    @Column(name = "public_port")
    private Integer publicPort;

    /** "ON" when the project should have a public internet link; null/"OFF" = private (the default). */
    @Column(name = "public_link_state", length = 10)
    private String publicLinkState;

    /** The public link as last seen (quick-tunnel links change when the tunnel restarts). */
    @Column(name = "public_link_url", length = 200)
    private String publicLinkUrl;

    public boolean isPublicLinkOn() {
        return "ON".equals(publicLinkState);
    }

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Integer version;
}
