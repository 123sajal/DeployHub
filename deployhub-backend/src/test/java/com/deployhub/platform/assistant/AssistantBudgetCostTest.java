package com.deployhub.platform.assistant;

import com.deployhub.platform.config.AssistantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantBudgetCostTest {

    private static AssistantProperties properties(boolean geminiFreeTier) {
        return new AssistantProperties(true, AssistantProperties.Provider.CLAUDE,
                new AssistantProperties.Claude("k", "claude-haiku-5-5", "claude-opus-5-5"),
                new AssistantProperties.Gemini("g", "gemini-3.5-flash-lite", "gemini-3.8-flash",
                        URI.create("https://example.invalid"), geminiFreeTier, false),
                20, 88, 50, 20, 300, Duration.ofSeconds(30));
    }

    private final AssistantBudget budget = new AssistantBudget(properties(true), null, null, Clock.systemUTC());

    @Test
    void costFollowsTheModelsPriceInThousandthsOfAPaisa() {
        // Haiku 5.5: 10 000 in x $0.10/M + 1 000 out x $0.50/M = $0.0015 = Rs 0.132 at 88 = 13 200 millipaise
        assertThat(budget.costMillipaise("claude-haiku-5-5", new AssistantModel.Usage(10_000, 1_000))).isEqualTo(13_200);
        // Opus 5.5: 20 000 x $4/M + 2 000 x $20/M = $0.12 = Rs 10.56
        assertThat(budget.costMillipaise("claude-opus-5-5", new AssistantModel.Usage(20_000, 2_000)))
                .isEqualTo(1_056_000);
    }

    @Test
    void anUnknownModelIsPricedLikeTheMostExpensiveOne() {
        assertThat(AssistantBudget.usdPerMillionTokens("claude-something-new")).extracting(BigDecimal::intValue)
                .containsExactly(10, 50);
        assertThat(AssistantBudget.usdPerMillionTokens("claude-opus-5")).extracting(BigDecimal::intValue)
                .containsExactly(5, 25);
    }

    @Test
    void geminiPricesAreKnownAndItsFreeTierCostsNothing() {
        // Flash-Lite 3.5: 10 000 x $0.30/M + 1 000 x $2.50/M = $0.0055 = Rs 0.484
        assertThat(budget.costMillipaise("gemini-3.5-flash-lite", new AssistantModel.Usage(10_000, 1_000))).isEqualTo(48_400);
        assertThat(AssistantBudget.usdPerMillionTokens("gemini-3.8-flash")).extracting(BigDecimal::toPlainString)
                .containsExactly("1.50", "7.50");

        AssistantProperties free = new AssistantProperties(true, AssistantProperties.Provider.GEMINI,
                properties(true).claude(), properties(true).gemini(), 20, 88, 50, 20, 300, Duration.ofSeconds(30));
        AssistantBudget freeBudget = new AssistantBudget(free, null, null, Clock.systemUTC());
        assertThat(freeBudget.costMillipaise("gemini-3.8-flash", new AssistantModel.Usage(500_000, 50_000))).isZero();
        assertThat(free.mayReadIdCards()).isFalse();
        assertThat(free.chatModel()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(properties(true).mayReadIdCards()).isTrue(); // Claude has no free tier

        AssistantProperties testing = new AssistantProperties(true, AssistantProperties.Provider.GEMINI,
                free.claude(), new AssistantProperties.Gemini("g", "gemini-3.5-flash-lite", "gemini-3.8-flash",
                        URI.create("https://example.invalid"), true, true), 20, 88, 50, 20, 300, Duration.ofSeconds(30));
        assertThat(testing.mayReadIdCards()).isTrue(); // switched on deliberately for testing
        assertThat(new AssistantBudget(testing, null, null, Clock.systemUTC())
                .costMillipaise("gemini-3.8-flash", new AssistantModel.Usage(1000, 100))).isZero();
    }

    @Test
    void evenATinyCallCostsSomething() {
        assertThat(budget.costMillipaise("claude-haiku-5-5", new AssistantModel.Usage(1, 0))).isEqualTo(1);
    }
}
