package com.deployhub.platform.assistant;

import com.deployhub.platform.config.AssistantProperties;
import com.deployhub.platform.models.entities.AssistantUsage;
import com.deployhub.platform.repositories.AssistantUsageRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Decides whether the AI may be called, and counts what each call cost. Days are India time, so the daily budget
 * starts again at midnight IST. The check happens before a call and the cost is known only after it, so the last
 * call of a day can go slightly over the budget (by one reply, a few rupees at most).
 */
@Component
public class AssistantBudget {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** Why the AI isn't used for a request (the built-in replies answer instead). */
    public enum Limit {
        /** No API key, or the assistant is switched off. */
        NOT_SET_UP,
        /** The day's spending cap for everyone is used up. */
        DAILY_BUDGET,
        /** This user or network has made its requests for today. */
        PERSONAL_LIMIT
    }

    private final AssistantProperties properties;
    private final AssistantUsageRepository usageRepository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AssistantBudget(AssistantProperties properties, AssistantUsageRepository usageRepository,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.usageRepository = usageRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public static String userSubject(Long userId) {
        return "user:" + userId;
    }

    public static String ipSubject(String ip) {
        return "ip:" + ip;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }

    /** Empty = the AI may be called for this subject now. */
    public Optional<Limit> check(String subject, int maxRequestsPerDay) {
        if (!properties.configured()) {
            return Optional.of(Limit.NOT_SET_UP);
        }
        String day = today().toString();
        if (dailyBudgetReached(day)) {
            return Optional.of(Limit.DAILY_BUDGET);
        }
        long used = usageRepository.findByUsageDayAndSubject(day, subject).map(AssistantUsage::getRequests).orElse(0L);
        return used >= maxRequestsPerDay ? Optional.of(Limit.PERSONAL_LIMIT) : Optional.empty();
    }

    /** Whether the AI can be used at all right now (key set and budget left). */
    public boolean available() {
        return properties.configured() && !dailyBudgetReached(today().toString());
    }

    /** Counts one AI request and its cost against the subject and the day. */
    public void record(String subject, String model, AssistantModel.Usage usage) {
        String day = today().toString();
        long cost = costMillipaise(model, usage);
        try {
            tx.executeWithoutResult(status -> {
                if (usageRepository.add(day, subject, usage.inputTokens(), usage.outputTokens(), cost) == 0) {
                    AssistantUsage row = new AssistantUsage();
                    row.setUsageDay(day);
                    row.setSubject(subject);
                    row.setRequests(1);
                    row.setInputTokens(usage.inputTokens());
                    row.setOutputTokens(usage.outputTokens());
                    row.setCostMillipaise(cost);
                    usageRepository.saveAndFlush(row);
                }
            });
        } catch (DataIntegrityViolationException e) {
            // A parallel request created the day's row first; add to it instead.
            tx.executeWithoutResult(status ->
                    usageRepository.add(day, subject, usage.inputTokens(), usage.outputTokens(), cost));
        }
    }

    /** Thousandths of a paisa spent today by everyone. */
    public long spentTodayMillipaise() {
        return usageRepository.totalCostOn(today().toString());
    }

    private boolean dailyBudgetReached(String day) {
        return usageRepository.totalCostOn(day) >= properties.dailyBudgetInr() * 100_000L;
    }

    /** What a call cost, from the token counts and the model's price, rounded up to the next thousandth of a paisa. */
    long costMillipaise(String model, AssistantModel.Usage usage) {
        if (properties.freeTier()) {
            return 0; // Gemini's free tier: only the per-user request limits apply
        }
        BigDecimal[] price = usdPerMillionTokens(model);
        BigDecimal usd = price[0].multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(price[1].multiply(BigDecimal.valueOf(usage.outputTokens())))
                .divide(BigDecimal.valueOf(1_000_000));
        return usd.multiply(BigDecimal.valueOf(properties.usdToInr())).multiply(BigDecimal.valueOf(100_000))
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /**
     * List prices in dollars per million input and output tokens (Claude and Gemini, October 2026; for Gemini Flash
     * the higher price announced from January 2027). An unknown model is priced like the most expensive one, so the
     * budget errs on the safe side.
     */
    static BigDecimal[] usdPerMillionTokens(String model) {
        if (model.startsWith("gemini-3.5-flash-lite")) {
            return prices("0.30", "2.50");
        }
        if (model.startsWith("gemini-3.1-flash-lite")) {
            return prices("0.25", "1.50");
        }
        if (model.matches("gemini-3\\.[68]-flash.*")) {
            return prices("1.50", "7.50");
        }
        if (model.startsWith("claude-haiku-5")) {
            return prices("0.10", "0.50");
        }
        if (model.startsWith("claude-haiku-4")) {
            return prices("1", "5");
        }
        if (model.startsWith("claude-sonnet-5")) {
            return prices("2", "10");
        }
        if (model.startsWith("claude-opus-5-5")) {
            return prices("4", "20");
        }
        if (model.startsWith("claude-opus")) {
            return prices("5", "25");
        }
        return prices("10", "50");
    }

    private static BigDecimal[] prices(String input, String output) {
        return new BigDecimal[]{new BigDecimal(input), new BigDecimal(output)};
    }
}
