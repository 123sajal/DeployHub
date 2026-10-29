package com.deployhub.platform.controllers;

import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.models.DTO.DatabaseImportResponseDTO;
import com.deployhub.platform.models.DTO.DatabaseResponseDTO;
import com.deployhub.platform.models.DTO.EnvApplyRequestDTO;
import com.deployhub.platform.models.DTO.EnvRequirementsResponseDTO;
import com.deployhub.platform.models.DTO.EnvVarResponseDTO;
import com.deployhub.platform.models.DTO.EnvVarValueRequestDTO;
import com.deployhub.platform.models.DTO.ProjectRoutesRequestDTO;
import com.deployhub.platform.models.DTO.PublicLinkResponseDTO;
import com.deployhub.platform.models.DTO.RouteResponseDTO;
import com.deployhub.platform.services.Interface.IEnvRequirementsService;
import com.deployhub.platform.services.Interface.IProjectDatabaseService;
import com.deployhub.platform.services.Interface.IProjectEnvService;
import com.deployhub.platform.services.Interface.IProjectPublicLinkService;
import com.deployhub.platform.services.Interface.IProjectRouteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/** A project's environment variables, routes, one-click database and public link. */
@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class ProjectSettingsController {

    private final IProjectEnvService envService;
    private final IProjectRouteService routeService;
    private final IProjectDatabaseService databaseService;
    private final IEnvRequirementsService envRequirementsService;
    private final IProjectPublicLinkService publicLinkService;

    /** Names only: values are write-only. */
    @GetMapping("/env")
    public List<EnvVarResponseDTO> listEnv(@PathVariable Long projectId) {
        return envService.list(projectId);
    }

    /** Creates or replaces one variable (the value is encrypted before it is stored). Applies at the next deployment. */
    @PutMapping("/env/{name}")
    public EnvVarResponseDTO setEnv(@PathVariable Long projectId, @PathVariable String name,
                                    @Valid @RequestBody EnvVarValueRequestDTO request) {
        return envService.set(projectId, name, request.value());
    }

    /** The variables the app needs (read from its repository) and which are still missing. */
    @GetMapping("/env/requirements")
    public EnvRequirementsResponseDTO envRequirements(@PathVariable Long projectId) {
        return envRequirementsService.requirements(projectId);
    }

    /** Fills in the given missing variables DeployHub can fill: generated secrets, example values, database aliases. */
    @PostMapping("/env/requirements/fill")
    public EnvRequirementsResponseDTO fillEnvRequirements(@PathVariable Long projectId,
                                                          @Valid @RequestBody EnvApplyRequestDTO request) {
        return envRequirementsService.fillIn(projectId, request.names());
    }

    @DeleteMapping("/env/{name}")
    public ResponseEntity<Void> deleteEnv(@PathVariable Long projectId, @PathVariable String name) {
        envService.delete(projectId, name);
        return ResponseEntity.noContent().build();
    }

    /** 200 with the database, or 204 when the project has none. Never includes the password. */
    @GetMapping("/database")
    public ResponseEntity<DatabaseResponseDTO> getDatabase(@PathVariable Long projectId) {
        return databaseService.get(projectId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 201. Creates the project's database (the first one also starts the shared MySQL server, which can take a
     * minute) and sets DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD and DATABASE_URL. The password is in this
     * response only. 409 if it already has one; 503 if Docker isn't running.
     */
    @PostMapping("/database")
    public ResponseEntity<DatabaseResponseDTO> createDatabase(@PathVariable Long projectId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(databaseService.create(projectId));
    }

    /** A new password (in this response only); DB_PASSWORD and DATABASE_URL are updated. Redeploy to use it. */
    @PostMapping("/database/reset-password")
    public DatabaseResponseDTO resetDatabasePassword(@PathVariable Long projectId) {
        return databaseService.resetPassword(projectId);
    }

    /**
     * Loads a SQL file (e.g. {@code mysqldump mydb > mydb.sql} from the user's computer) into the project's database.
     * {@code replace=true} deletes the current data first; without it, a database that already has tables gets 409.
     * 400 with errors.file when MySQL can't run the file. Up to 50 MB.
     */
    @PostMapping(path = "/database/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DatabaseImportResponseDTO importDatabase(@PathVariable Long projectId,
                                                    @RequestParam("file") MultipartFile file,
                                                    @RequestParam(name = "replace", defaultValue = "false") boolean replace)
            throws java.io.IOException {
        if (file.isEmpty()) {
            throw new FieldValidationException("file", "Choose a .sql file to import.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".sql")) {
            throw new FieldValidationException("file", "Choose a .sql file (an export of your database).");
        }
        try (java.io.InputStream in = file.getInputStream()) {
            return databaseService.importData(projectId, in, replace);
        }
    }

    /** The project's database as a .sql file download (tables, data, views, triggers). */
    @GetMapping("/database/export")
    public ResponseEntity<StreamingResponseBody> exportDatabase(@PathVariable Long projectId) {
        String fileName = databaseService.exportFileName(projectId);
        java.nio.file.Path file = databaseService.exportData(projectId);
        StreamingResponseBody body = out -> {
            try {
                java.nio.file.Files.copy(file, out);
            } finally {
                java.nio.file.Files.deleteIfExists(file);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("application/sql"))
                .body(body);
    }

    /** 204. Deletes the database with ALL its data, and its environment variables. */
    @DeleteMapping("/database")
    public ResponseEntity<Void> removeDatabase(@PathVariable Long projectId) {
        databaseService.remove(projectId);
        return ResponseEntity.noContent().build();
    }

    /** Whether the project has a public internet link, and the current link. */
    @GetMapping("/public-link")
    public PublicLinkResponseDTO getPublicLink(@PathVariable Long projectId) {
        return publicLinkService.get(projectId);
    }

    /** Gives the (live) project a public https link; 409 if it isn't live, 503 if the tunnel can't start. */
    @PostMapping("/public-link")
    public PublicLinkResponseDTO enablePublicLink(@PathVariable Long projectId) {
        return publicLinkService.enable(projectId);
    }

    @DeleteMapping("/public-link")
    public PublicLinkResponseDTO disablePublicLink(@PathVariable Long projectId) {
        return publicLinkService.disable(projectId);
    }

    @GetMapping("/routes")
    public List<RouteResponseDTO> listRoutes(@PathVariable Long projectId) {
        return routeService.list(projectId);
    }

    /** Replaces all routes, e.g. {"routes":[{"pathPrefix":"/api","targetProjectId":12}]}. Applies at once. */
    @PutMapping("/routes")
    public List<RouteResponseDTO> replaceRoutes(@PathVariable Long projectId,
                                                @Valid @RequestBody ProjectRoutesRequestDTO request) {
        return routeService.replace(projectId, request);
    }
}
