package com.deployhub.platform.controllers;

import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.models.DTO.DetectRequestDTO;
import com.deployhub.platform.models.DTO.DetectResponseDTO;
import com.deployhub.platform.models.DTO.PageResponseDTO;
import com.deployhub.platform.models.DTO.ProjectRequestDTO;
import com.deployhub.platform.models.DTO.ProjectResponseDTO;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.security.RepositoryCheckThrottle;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IProjectDetectionService;
import com.deployhub.platform.services.Interface.IProjectService;
import com.deployhub.platform.services.Interface.IRepositoryFiles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
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

import java.net.URI;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final IProjectService projectService;
    private final IProjectDetectionService detectionService;
    private final IRepositoryFiles repositoryFiles;
    private final ICurrentUserService currentUserService;
    private final RepositoryCheckThrottle repositoryChecks;

    @GetMapping
    public PageResponseDTO<ProjectResponseDTO> list(@RequestParam(required = false) String search,
                                                    @RequestParam(required = false) ApplicationType appType,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return projectService.list(search, appType, page, size);
    }

    /** Looks at the repository's files and suggests the type and commands (nothing is saved). */
    @PostMapping("/detect")
    public DetectResponseDTO detect(@Valid @RequestBody DetectRequestDTO request) {
        return detectionService.detect(request);
    }

    @GetMapping("/{id}")
    public ProjectResponseDTO get(@PathVariable Long id) {
        return projectService.get(id);
    }

    @PostMapping
    public ResponseEntity<ProjectResponseDTO> create(@Valid @RequestBody ProjectRequestDTO request) {
        checkToken(request);
        ProjectResponseDTO created = projectService.create(request);
        return ResponseEntity.created(URI.create("/api/projects/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ProjectResponseDTO update(@PathVariable Long id, @Valid @RequestBody ProjectRequestDTO request) {
        projectService.get(id); // 404 for someone else's project, before anything is sent to a Git host
        checkToken(request);
        return projectService.update(id, request);
    }

    /** Forgets the project's access token (for a private repository). */
    @DeleteMapping("/{id}/repository-token")
    public ProjectResponseDTO removeRepositoryToken(@PathVariable Long id) {
        return projectService.removeRepositoryToken(id);
    }

    /**
     * A new access token is tried against the repository before it is saved (outside any database transaction: it
     * takes a network round trip), so a wrong or too-narrow token is reported on the form, not at the next deploy.
     */
    private void checkToken(ProjectRequestDTO request) {
        if (request.repositoryToken() == null) {
            return;
        }
        repositoryChecks.check(currentUserService.currentUserId()); // no unlimited token testing
        try {
            repositoryFiles.checkAccess(request.repositoryUrl(), request.branch(), request.repositoryToken());
        } catch (IRepositoryFiles.RepositoryNotReadable e) {
            throw new FieldValidationException("repositoryToken", e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
