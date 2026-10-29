package com.deployhub.platform.models.DTO;

/**
 * @param text   the answer
 * @param source AI = written by the AI; BUILT_IN = a simple built-in answer (AI not set up, limit reached, or down)
 * @param notice why a built-in answer was given, or null
 * @param saved  true when this is a saved AI explanation (showing it again costs nothing)
 */
public record AssistantReplyResponseDTO(String text, Source source, String notice, boolean saved) {

    public enum Source { AI, BUILT_IN }
}
