package com.deployhub.platform.models.DTO;

/**
 * @param ok         the answer can be used (with a suggestion, the user chooses: the suggestion or their own)
 * @param value      the answer cleaned up (trimmed; email in lower case)
 * @param message    what the assistant says next, or what is wrong
 * @param suggestion a likely correction the user can accept with one click (e.g. the email with gmail.com), or null
 */
public record SignupCheckResponseDTO(boolean ok, String value, String message, String suggestion) {
}
