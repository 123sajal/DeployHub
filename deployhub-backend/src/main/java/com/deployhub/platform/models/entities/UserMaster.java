package com.deployhub.platform.models.entities;

import com.deployhub.platform.models.enums.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Always stored normalised: trimmed and lower-case (enforced by a DB check constraint too). */
    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** Encoded with a DelegatingPasswordEncoder, e.g. "{bcrypt}$2a$10$...". Never the raw password. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** When a deploy last made one of the user's projects free (null = none yet). */
    @Column(name = "free_project_claimed_at")
    private Instant freeProjectClaimedAt;

    /** How many free projects the user has used (see deployhub.billing.free-projects). Deleting one doesn't give it back. */
    @Column(name = "free_projects_used", nullable = false)
    private int freeProjectsUsed;
}
