package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.exceptions.ConflictException;
import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.models.DTO.EnvVarResponseDTO;
import com.deployhub.platform.models.entities.ProjectEnvVar;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.ProjectEnvVarRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.security.SecretCipher;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IProjectEnvService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProjectEnvService implements IProjectEnvService {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,99}$");
    /** Set by DeployHub itself; overriding them would break the health check and the address. */
    private static final Set<String> RESERVED = Set.of("PORT", "SERVER_PORT");
    private static final int MAX_PER_PROJECT = 50;

    private final ProjectRepository projects;
    private final ProjectEnvVarRepository variables;
    private final SecretCipher cipher;
    private final ICurrentUserService currentUserService;
    private final IAuditService auditService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<EnvVarResponseDTO> list(Long projectId) {
        requireOwned(projectId);
        return variables.findByProjectIdOrderByNameAsc(projectId).stream().map(ProjectEnvService::toResponse).toList();
    }

    @Override
    @Transactional
    public EnvVarResponseDTO set(Long projectId, String name, String value) {
        requireOwned(projectId);
        if (name == null || !NAME.matcher(name).matches()) {
            throw new FieldValidationException("name",
                    "must start with a letter or _, then letters, digits or _ (at most 100)");
        }
        if (RESERVED.contains(name.toUpperCase(java.util.Locale.ROOT))) {
            throw new FieldValidationException("name", name + " is set by DeployHub (use the project's port setting)");
        }
        if (!cipher.isConfigured()) {
            throw new ConflictException("Environment variables need an encryption key. Set DEPLOYHUB_SECRETS_KEY "
                    + "(openssl rand -base64 32) in deployhub-backend/.env and restart the backend.");
        }
        Instant now = clock.instant();
        String encrypted = cipher.encrypt(value);
        ProjectEnvVar variable = variables.findByProjectIdAndName(projectId, name)
                .map(existing -> {
                    existing.replaceValue(encrypted, now);
                    return existing;
                })
                .orElseGet(() -> {
                    if (variables.countByProjectId(projectId) >= MAX_PER_PROJECT) {
                        throw new ConflictException("A project can have at most " + MAX_PER_PROJECT
                                + " environment variables");
                    }
                    return new ProjectEnvVar(projectId, name, encrypted, now);
                });
        variables.saveAndFlush(variable);
        auditService.record(AuditEventType.ENV_VAR_SET, currentUserService.currentUserId(), null, null,
                "project #" + projectId + ": " + name);
        return toResponse(variable);
    }

    @Override
    @Transactional
    public void delete(Long projectId, String name) {
        requireOwned(projectId);
        ProjectEnvVar variable = variables.findByProjectIdAndName(projectId, name)
                .orElseThrow(() -> new ConflictException("Project #" + projectId + " has no variable " + name));
        variables.delete(variable);
        auditService.record(AuditEventType.ENV_VAR_DELETED, currentUserService.currentUserId(), null, null,
                "project #" + projectId + ": " + name);
    }

    private void requireOwned(Long projectId) {
        projects.findByIdAndOwnerId(projectId, currentUserService.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    private static EnvVarResponseDTO toResponse(ProjectEnvVar variable) {
        return new EnvVarResponseDTO(variable.getName(), variable.getCreatedAt(), variable.getUpdatedAt());
    }
}
