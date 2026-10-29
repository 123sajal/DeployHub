package com.deployhub.platform.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The AI's explanation of a failed deployment, kept so showing it again costs nothing. */
@Entity
@Table(name = "deployment_explanations")
@Getter
@Setter
@NoArgsConstructor
public class DeploymentExplanation {

    @Id
    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(nullable = false, length = 4000)
    private String explanation;

    @Column(nullable = false, length = 60)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
