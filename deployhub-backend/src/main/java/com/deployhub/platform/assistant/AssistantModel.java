package com.deployhub.platform.assistant;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * The AI behind the assistant. {@link ClaudeAssistantModel} calls the Claude API; tests use a fake. Every method
 * throws {@link AssistantUnavailableException} when the AI can't answer (no key, network, rate limit, outage), so
 * callers fall back to the built-in replies.
 */
public interface AssistantModel {

    enum Role { USER, ASSISTANT }

    record Turn(Role role, String text) {
    }

    /**
     * @param model        model id, e.g. claude-haiku-5-5
     * @param system       instructions (DeployHub facts and rules)
     * @param turns        the conversation, oldest first, ending with the user
     * @param maxTokens    upper limit for the reply (thinking included)
     * @param thorough     think harder (failed deployments), else answer quickly (chat)
     */
    record Request(String model, String system, List<Turn> turns, long maxTokens, boolean thorough) {
    }

    record Usage(long inputTokens, long outputTokens) {
        public static final Usage NONE = new Usage(0, 0);
    }

    /**
     * @param refused true when the model declined to answer (stop reason "refusal"); text is then empty or partial
     * @param cutOff  true when the reply hit maxTokens and may end mid-sentence
     */
    record Reply(String text, Usage usage, boolean refused, boolean cutOff) {
    }

    /** What the AI read from a student ID photo. Empty strings mean "not on the card / not readable". */
    record IdCardReading(
            @JsonPropertyDescription("true if the text on the card can be read")
            boolean readable,
            @JsonPropertyDescription("true if this is a student identity card issued by a school, college or university")
            boolean studentIdCard,
            @JsonPropertyDescription("the student's full name as printed on the card, or empty")
            String nameOnCard,
            @JsonPropertyDescription("the school, college or university name, or empty")
            String institution,
            @JsonPropertyDescription("the last day the card is valid as YYYY-MM-DD (use the last day of the month or year if only those are shown), or empty")
            String validUntil,
            @JsonPropertyDescription("if not readable or not a student ID: one short sentence telling the student what to fix, else empty")
            String problem) {
    }

    record IdCardResult(IdCardReading reading, Usage usage, boolean refused) {
    }

    Reply ask(Request request);

    /** Reads a student ID card photo (JPEG bytes, metadata already removed). */
    IdCardResult readStudentId(String model, byte[] jpeg);
}
