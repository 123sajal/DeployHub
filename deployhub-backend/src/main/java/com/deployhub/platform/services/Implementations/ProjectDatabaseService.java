package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.exceptions.ConflictException;
import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.exceptions.ServiceUnavailableException;
import com.deployhub.platform.execution.ManagedDatabaseServer;
import com.deployhub.platform.models.DTO.DatabaseResponseDTO;
import com.deployhub.platform.models.entities.DatabaseServer;
import com.deployhub.platform.models.entities.ProjectDatabase;
import com.deployhub.platform.models.entities.ProjectEnvVar;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.DatabaseServerRepository;
import com.deployhub.platform.repositories.ProjectDatabaseRepository;
import com.deployhub.platform.repositories.ProjectEnvVarRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.security.SecretCipher;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IProjectDatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Creates a database per project on the shared MySQL server and hands its connection details to the app as
 * environment variables. The work on the server happens outside any database transaction (it can take a
 * minute the first time, while MySQL initialises); every server operation is idempotent, so a failed request
 * can just be repeated.
 */
@Slf4j
@Service
public class ProjectDatabaseService implements IProjectDatabaseService {

    static final String ENGINE = "MYSQL";
    /** What the app gets. These names match common Node/Express setups (and DATABASE_URL for many frameworks). */
    static final List<String> VARIABLES = List.of("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD",
            "DATABASE_URL");
    private static final String ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final ProjectRepository projects;
    private final ProjectDatabaseRepository databases;
    private final DatabaseServerRepository servers;
    private final ProjectEnvVarRepository envVars;
    private final SecretCipher cipher;
    private final ManagedDatabaseServer server;
    private final ICurrentUserService currentUserService;
    private final IAuditService auditService;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ProjectDatabaseService(ProjectRepository projects, ProjectDatabaseRepository databases,
                                  DatabaseServerRepository servers, ProjectEnvVarRepository envVars,
                                  SecretCipher cipher, ManagedDatabaseServer server,
                                  ICurrentUserService currentUserService, IAuditService auditService,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.projects = projects;
        this.databases = databases;
        this.servers = servers;
        this.envVars = envVars;
        this.cipher = cipher;
        this.server = server;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Override
    public Optional<DatabaseResponseDTO> get(Long projectId) {
        return tx.execute(s -> {
            owned(projectId);
            return databases.findByProjectId(projectId).map(db -> toResponse(db, null));
        });
    }

    @Override
    public DatabaseResponseDTO create(Long projectId) {
        Long userId = currentUserService.currentUserId();
        ProjectMaster project = tx.execute(s -> {
            ProjectMaster p = owned(projectId);
            if (databases.findByProjectId(projectId).isPresent()) {
                throw new ConflictException("Project " + p.getName() + " already has a database");
            }
            return p;
        });
        requireCipher();
        String name = identifier(project);
        String password = randomPassword();
        onServer(admin -> server.createDatabase(admin, name, name, password));
        ProjectDatabase saved;
        try {
            saved = tx.execute(s -> {
                ProjectDatabase database = databases.saveAndFlush(
                        new ProjectDatabase(projectId, ENGINE, name, name, clock.instant()));
                writeVariables(projectId, variables(name, password));
                auditService.record(AuditEventType.DATABASE_CREATED, userId, null, null,
                        "project #" + projectId + ": database " + name);
                return database;
            });
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException("Project " + project.getName() + " already has a database");
        }
        return toResponse(saved, password);
    }

    @Override
    public DatabaseResponseDTO resetPassword(Long projectId) {
        Long userId = currentUserService.currentUserId();
        ProjectDatabase database = tx.execute(s -> {
            owned(projectId);
            return databases.findByProjectId(projectId).orElseThrow(() -> noDatabase(projectId));
        });
        requireCipher();
        String password = randomPassword();
        onServer(admin -> server.changePassword(admin, database.getUsername(), password));
        tx.executeWithoutResult(s -> {
            writeVariables(projectId, variables(database.getDatabaseName(), password));
            auditService.record(AuditEventType.DATABASE_PASSWORD_RESET, userId, null, null,
                    "project #" + projectId + ": database " + database.getDatabaseName());
        });
        return toResponse(database, password);
    }

    @Override
    public void remove(Long projectId) {
        Long userId = currentUserService.currentUserId();
        ProjectDatabase database = tx.execute(s -> {
            owned(projectId);
            return databases.findByProjectId(projectId).orElseThrow(() -> noDatabase(projectId));
        });
        onServer(admin -> server.dropDatabase(admin, database.getDatabaseName(), database.getUsername()));
        tx.executeWithoutResult(s -> {
            databases.deleteById(database.getId());
            VARIABLES.forEach(variable -> envVars.findByProjectIdAndName(projectId, variable).ifPresent(envVars::delete));
            auditService.record(AuditEventType.DATABASE_REMOVED, userId, null, null,
                    "project #" + projectId + ": database " + database.getDatabaseName());
        });
    }

    @Override
    public void dropAfterProjectDeleted(String databaseName, String username) {
        try {
            String admin = newTx.execute(s -> servers.findByEngine(ENGINE)
                    .map(row -> cipher.decrypt(row.getAdminPasswordEncrypted())).orElse(null));
            if (admin != null) {
                server.ensureRunning(admin);
                server.dropDatabase(admin, databaseName, username);
            }
        } catch (RuntimeException e) {
            log.warn("Could not drop database {} of a deleted project", databaseName, e);
        }
    }

    /** Runs work on the server with its admin password (created on first use), reporting Docker problems as 503. */
    private void onServer(java.util.function.Consumer<String> work) {
        String admin = adminPassword();
        try {
            server.ensureRunning(admin);
            work.accept(admin);
        } catch (IllegalStateException e) {
            throw new ServiceUnavailableException(e.getMessage(), e);
        }
    }

    /** The server's admin password: generated once, stored encrypted. The unique engine row settles a race. */
    private String adminPassword() {
        requireCipher();
        Optional<String> existing = tx.execute(s -> servers.findByEngine(ENGINE)
                .map(row -> cipher.decrypt(row.getAdminPasswordEncrypted())));
        if (existing.isPresent()) {
            return existing.get();
        }
        String generated = randomPassword();
        try {
            tx.executeWithoutResult(s -> servers.saveAndFlush(
                    new DatabaseServer(ENGINE, cipher.encrypt(generated), clock.instant())));
            return generated;
        } catch (DataIntegrityViolationException raced) {
            return tx.execute(s -> cipher.decrypt(servers.findByEngine(ENGINE).orElseThrow().getAdminPasswordEncrypted()));
        }
    }

    private Map<String, String> variables(String name, String password) {
        ManagedDatabaseServer.Endpoints endpoints = server.endpoints();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("DB_HOST", endpoints.appHost());
        values.put("DB_PORT", String.valueOf(endpoints.appPort()));
        values.put("DB_NAME", name);
        values.put("DB_USER", name);
        values.put("DB_PASSWORD", password);
        values.put("DATABASE_URL", "mysql://" + name + ":" + password + "@" + endpoints.appHost() + ":"
                + endpoints.appPort() + "/" + name);
        return values;
    }

    /** Creates or replaces each variable (encrypted). Replacing an earlier hand-made DB_HOST etc. is intended. */
    private void writeVariables(Long projectId, Map<String, String> values) {
        Instant now = clock.instant();
        values.forEach((variable, value) -> {
            String encrypted = cipher.encrypt(value);
            ProjectEnvVar row = envVars.findByProjectIdAndName(projectId, variable)
                    .map(existing -> {
                        existing.replaceValue(encrypted, now);
                        return existing;
                    })
                    .orElseGet(() -> new ProjectEnvVar(projectId, variable, encrypted, now));
            envVars.save(row);
        });
        envVars.flush();
    }

    /** "p12_employee_management": unique per project (the id) and readable (the name); MySQL allows 32 chars. */
    static String identifier(ProjectMaster project) {
        String readable = project.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        String id = "p" + project.getId() + "_" + readable;
        return id.length() > 32 ? id.substring(0, 32) : id;
    }

    private String randomPassword() {
        StringBuilder password = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            password.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return password.toString();
    }

    private void requireCipher() {
        if (!cipher.isConfigured()) {
            throw new ConflictException("Databases need an encryption key for their passwords. Set "
                    + "DEPLOYHUB_SECRETS_KEY (openssl rand -base64 32) in deployhub-backend/.env and restart.");
        }
    }

    private ProjectMaster owned(Long projectId) {
        return projects.findByIdAndOwnerId(projectId, currentUserService.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    private static ConflictException noDatabase(Long projectId) {
        return new ConflictException("Project #" + projectId + " has no database");
    }

    private DatabaseResponseDTO toResponse(ProjectDatabase database, String password) {
        ManagedDatabaseServer.Endpoints endpoints = server.endpoints();
        return new DatabaseResponseDTO("MySQL", database.getDatabaseName(), database.getUsername(),
                endpoints.appHost(), endpoints.appPort(), endpoints.localHost(), endpoints.localPort(), password,
                database.getCreatedAt(), VARIABLES);
    }
}
