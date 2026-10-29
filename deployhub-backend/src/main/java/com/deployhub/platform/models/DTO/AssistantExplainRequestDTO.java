package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.AssistantTopic;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param deploymentId required for DEPLOYMENT_FAILED
 * @param projectId    optional for the other topics: adds the project's type and commands
 * @param errorText    the error the page showed (for the other topics)
 */
public record AssistantExplainRequestDTO(
        @NotNull
        AssistantTopic topic,

        Long deploymentId,

        Long projectId,

        @Size(max = 2000)
        String errorText) {
}
