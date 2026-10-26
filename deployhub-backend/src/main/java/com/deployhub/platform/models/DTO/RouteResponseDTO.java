package com.deployhub.platform.models.DTO;

public record RouteResponseDTO(String pathPrefix, Long targetProjectId, String targetProjectName) {
}
