package com.deployhub.platform.controllers;

import com.deployhub.platform.models.DTO.DeploymentCreationResult;
import com.deployhub.platform.models.DTO.DeploymentHistoryResponseDTO;
import com.deployhub.platform.models.DTO.DeploymentRequestDTO;
import com.deployhub.platform.models.DTO.DeploymentResponseDTO;
import com.deployhub.platform.models.DTO.LogPageResponseDTO;
import com.deployhub.platform.models.DTO.PageResponseDTO;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.services.Interface.IDeploymentService;
import com.deployhub.platform.services.Interface.ILogStreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DeploymentController {

    private final IDeploymentService deploymentService;
    private final ILogStreamService logStreamService;

    @GetMapping("/deployments")
    public PageResponseDTO<DeploymentResponseDTO> list(@RequestParam(required = false) Long projectId,
                                                       @RequestParam(required = false) DeploymentStatus status,
                                                       @RequestParam(defaultValue = "0") @Min(0) int page,
                                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return deploymentService.list(projectId, status, page, size);
    }

    @GetMapping("/deployments/{id}")
    public DeploymentResponseDTO get(@PathVariable Long id) {
        return deploymentService.get(id);
    }

    /**
     * 202 Accepted: the deployment is queued, not finished. Poll the Location URL for progress.
     * Send an {@code Idempotency-Key} header to make retries safe: repeating the same request with the
     * same key returns the original deployment (200, {@code Idempotent-Replayed: true}) instead of a new one.
     */
    @PostMapping("/projects/{projectId}/deployments")
    public ResponseEntity<DeploymentResponseDTO> create(@PathVariable Long projectId,
                                                        @Valid @RequestBody(required = false) DeploymentRequestDTO request,
                                                        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return created(deploymentService.create(projectId, request, idempotencyKey));
    }

    /**
     * Rolls back to deployment {@code id}: queues a NEW deployment that redeploys it (202, like a deploy).
     * Only a deployment that was LIVE and has been replaced (SUPERSEDED) qualifies, otherwise 409.
     * Supports {@code Idempotency-Key} exactly like creating a deployment.
     */
    @PostMapping("/deployments/{id}/rollback")
    public ResponseEntity<DeploymentResponseDTO> rollback(@PathVariable Long id,
                                                          @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return created(deploymentService.rollback(id, idempotencyKey));
    }

    private static ResponseEntity<DeploymentResponseDTO> created(DeploymentCreationResult result) {
        URI location = URI.create("/api/deployments/" + result.deployment().id());
        if (result.replayed()) {
            return ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true").body(result.deployment());
        }
        return ResponseEntity.accepted().location(location).body(result.deployment());
    }

    /** 204. Only FAILED, CANCELLED or SUPERSEDED deployments; a LIVE or in-progress one gives 409. */
    @DeleteMapping("/deployments/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        deploymentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/deployments/{id}/cancel")
    public DeploymentResponseDTO cancel(@PathVariable Long id) {
        return deploymentService.cancel(id);
    }

    /** Every status change with its reason and actor, plus the queue job's attempts and retry schedule. */
    @GetMapping("/deployments/{id}/history")
    public DeploymentHistoryResponseDTO history(@PathVariable Long id) {
        return deploymentService.history(id);
    }

    /**
     * Live logs as Server-Sent Events: {@code log} events (id = cursor), {@code status} and a final {@code end}.
     * Resumes after {@code after}, or after the {@code Last-Event-ID} header that browsers send when they
     * reconnect by themselves, whichever is further. 429 when too many streams are open.
     */
    @GetMapping(path = "/deployments/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable Long id,
                                 @RequestParam(defaultValue = "0") @Min(0) long after,
                                 @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return logStreamService.open(id, Math.max(after, parseCursor(lastEventId)));
    }

    private static long parseCursor(String lastEventId) {
        try {
            return lastEventId == null ? 0 : Math.max(0, Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException e) {
            return 0; // not one of our ids: start from the `after` parameter
        }
    }

    @GetMapping("/deployments/{id}/logs")
    public LogPageResponseDTO logs(@PathVariable Long id,
                                   @RequestParam(defaultValue = "0") @Min(0) long after,
                                   @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        return deploymentService.logs(id, after, limit);
    }
}
