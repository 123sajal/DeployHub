package com.deployhub.platform.models.entities;

import com.deployhub.platform.models.enums.AccountStatus;
import com.deployhub.platform.models.enums.Occupation;
import com.deployhub.platform.models.enums.StudentProof;
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
import java.time.LocalDate;

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

    /** Student or working professional, as told to the sign-up assistant (null = not asked, e.g. older accounts). */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Occupation occupation;

    /** When the email was proven with a code (null = signed up with the simple form, not checked). */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** Last day (India time) the student discount applies; null = not a verified student. */
    @Column(name = "student_verified_until")
    private LocalDate studentVerifiedUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "student_verified_by", length = 20)
    private StudentProof studentVerifiedBy;

    /** A verified student on this day (the discount applies). */
    public boolean isVerifiedStudentOn(LocalDate day) {
        return studentVerifiedUntil != null && !studentVerifiedUntil.isBefore(day);
    }
}
