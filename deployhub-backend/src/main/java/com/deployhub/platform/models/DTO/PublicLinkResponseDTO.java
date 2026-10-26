package com.deployhub.platform.models.DTO;

/**
 * @param enabled  whether the project has a public internet link
 * @param url      the public link (https://...trycloudflare.com), null when off
 * @param localUrl the project's address on this machine, null before its first real deployment
 */
public record PublicLinkResponseDTO(boolean enabled, String url, String localUrl) {
}
