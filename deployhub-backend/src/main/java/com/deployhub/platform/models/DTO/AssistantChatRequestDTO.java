package com.deployhub.platform.models.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param page     where the user is (e.g. "signup" or "/app/projects/12"), so answers fit the screen
 * @param messages the conversation so far, oldest first, ending with the user's new question. The browser keeps it;
 *                 DeployHub doesn't store chats.
 */
public record AssistantChatRequestDTO(
        @Size(max = 100)
        String page,

        @NotEmpty
        @Size(max = 12)
        List<@Valid @NotNull Turn> messages) {

    public enum Role { USER, ASSISTANT }

    public record Turn(@NotNull Role role, @NotBlank @Size(max = 2000) String text) {
    }
}
