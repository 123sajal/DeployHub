package com.deployhub.platform.models.DTO;

/**
 * @param aiAvailable the AI can answer now (key set and today's budget not used up)
 * @param idCardCheck student ID photos can be checked (the AI is available and isn't on a free tier that may use
 *                    the content); otherwise students use their college email
 */
public record AssistantStatusResponseDTO(boolean aiAvailable, boolean idCardCheck) {
}
