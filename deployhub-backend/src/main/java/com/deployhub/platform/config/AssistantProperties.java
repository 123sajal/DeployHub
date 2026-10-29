package com.deployhub.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * The AI assistant. Without an API key for the chosen provider, or when the day's budget is used up, it answers with
 * simple built-in replies instead; DeployHub itself works the same either way.
 *
 * @param enabled                  false = never call the AI (built-in replies only)
 * @param provider                 which AI answers: GEMINI (Google, has a free tier) or CLAUDE (Anthropic)
 * @param claude                   Anthropic settings
 * @param gemini                   Google settings
 * @param dailyBudgetInr           AI spending allowed per day for everyone together, in rupees; then built-in replies
 * @param usdToInr                 exchange rate used to turn the API's dollar prices into rupees
 * @param maxRequestsPerUserPerDay AI requests one signed-in user may make per day
 * @param maxRequestsPerIpPerDay   AI requests per day from one network before signing in
 * @param maxLogLines              last lines of a failed deployment's log the AI reads
 * @param timeout                  how long one AI request may take
 */
@ConfigurationProperties(prefix = "deployhub.assistant")
public record AssistantProperties(boolean enabled, Provider provider, Claude claude, Gemini gemini,
                                  int dailyBudgetInr, double usdToInr, int maxRequestsPerUserPerDay,
                                  int maxRequestsPerIpPerDay, int maxLogLines, Duration timeout) {

    public enum Provider { CLAUDE, GEMINI }

    /**
     * @param apiKey       from console.anthropic.com (a Claude subscription can't be used for an app's users)
     * @param chatModel    chat, sign-up questions, short explanations and the student ID check
     * @param explainModel explaining failed deployments (reads long logs)
     */
    public record Claude(String apiKey, String chatModel, String explainModel) {
    }

    /**
     * @param apiKey       from aistudio.google.com (API keys)
     * @param chatModel    chat, sign-up questions, short explanations and the student ID check
     * @param explainModel explaining failed deployments (reads long logs)
     * @param apiUrl       Google's API address (tests point it at a local fake)
     * @param freeTier     the key's Google project uses the free tier: calls cost nothing (only the per-user limits
     *                     apply), but Google may use what it receives to improve its products, so student ID photos
     *                     are never sent
     * @param idCardsOnFreeTier send student ID photos even on the free tier (for testing with your own or a sample
     *                     card; off by default)
     */
    public record Gemini(String apiKey, String chatModel, String explainModel, URI apiUrl, boolean freeTier,
                         boolean idCardsOnFreeTier) {
    }

    public String apiKey() {
        return provider == Provider.CLAUDE ? claude.apiKey() : gemini.apiKey();
    }

    public String chatModel() {
        return provider == Provider.CLAUDE ? claude.chatModel() : gemini.chatModel();
    }

    public String explainModel() {
        return provider == Provider.CLAUDE ? claude.explainModel() : gemini.explainModel();
    }

    public boolean configured() {
        String key = apiKey();
        return enabled && key != null && !key.isBlank();
    }

    /** Calls cost nothing: Gemini's free tier. */
    public boolean freeTier() {
        return provider == Provider.GEMINI && gemini.freeTier();
    }

    /**
     * Student ID photos may go to the AI: not on a free tier, whose provider may use the content, unless that was
     * switched on deliberately (testing).
     */
    public boolean mayReadIdCards() {
        return !freeTier() || gemini.idCardsOnFreeTier();
    }
}
