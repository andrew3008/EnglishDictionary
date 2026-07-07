package space.br1440.platform.devtools.opusmcp.budget;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * In-memory daily budget tracking for {@code generate_code_with_opus}.
 *
 * <p>Counters reset automatically when the (injected) current date changes. A limit of {@code 0}
 * means that dimension is disabled. No prompts, context, secrets, or outputs are ever stored — only
 * aggregate counters. Persistence is intentionally out of scope for Phase 3.
 */
public final class BudgetTracker {

    /** Configurable daily limits and pricing. A value of {@code 0} disables that dimension. */
    public record BudgetLimits(
            long dailyRequestLimit,
            long dailyInputCharLimit,
            long dailyEstimatedTokenLimit,
            double dailyCostLimit,
            double pricePer1kInputTokens,
            double pricePer1kOutputTokens) {

        public static BudgetLimits disabled() {
            return new BudgetLimits(0, 0, 0, 0d, 0d, 0d);
        }
    }

    public record BudgetDecision(boolean allowed, String reason) {
        public static BudgetDecision allow() {
            return new BudgetDecision(true, "within budget");
        }

        public static BudgetDecision deny(String reason) {
            return new BudgetDecision(false, reason);
        }
    }

    public record Snapshot(
            LocalDate date,
            long requestCount,
            long inputCharCount,
            long estimatedInputTokens,
            long estimatedOutputTokens,
            double estimatedCost) {
    }

    private final BudgetLimits limits;
    private final Supplier<LocalDate> clock;

    private LocalDate lastResetDate;
    private long dailyRequestCount;
    private long dailyInputCharCount;
    private long dailyEstimatedInputTokens;
    private long dailyEstimatedOutputTokens;
    private double dailyEstimatedCost;

    public BudgetTracker(BudgetLimits limits) {
        this(limits, LocalDate::now);
    }

    public BudgetTracker(BudgetLimits limits, Supplier<LocalDate> clock) {
        this.limits = limits == null ? BudgetLimits.disabled() : limits;
        this.clock = clock == null ? LocalDate::now : clock;
        this.lastResetDate = this.clock.get();
    }

    /**
     * Pre-flight budget check using estimated input usage. Output-dependent dimensions (cost,
     * output tokens) are enforced only by post-call accounting, but the projected cost from input
     * is checked here when a cost limit is configured.
     */
    public synchronized BudgetDecision preCheck(long inputChars, long estimatedInputTokens) {
        rollover();
        if (limits.dailyRequestLimit() > 0
                && dailyRequestCount + 1 > limits.dailyRequestLimit()) {
            return BudgetDecision.deny("daily request limit reached");
        }
        if (limits.dailyInputCharLimit() > 0
                && dailyInputCharCount + Math.max(0, inputChars) > limits.dailyInputCharLimit()) {
            return BudgetDecision.deny("daily input character limit reached");
        }
        if (limits.dailyEstimatedTokenLimit() > 0
                && dailyEstimatedInputTokens + Math.max(0, estimatedInputTokens)
                        > limits.dailyEstimatedTokenLimit()) {
            return BudgetDecision.deny("daily estimated token limit reached");
        }
        if (limits.dailyCostLimit() > 0d) {
            double projected = dailyEstimatedCost
                    + costOf(Math.max(0, estimatedInputTokens), 0);
            if (projected > limits.dailyCostLimit()) {
                return BudgetDecision.deny("daily cost limit reached");
            }
        }
        return BudgetDecision.allow();
    }

    /** Records actual usage after a successful model call. */
    public synchronized void record(long inputChars, long inputTokens, long outputTokens) {
        rollover();
        dailyRequestCount++;
        dailyInputCharCount += Math.max(0, inputChars);
        dailyEstimatedInputTokens += Math.max(0, inputTokens);
        dailyEstimatedOutputTokens += Math.max(0, outputTokens);
        dailyEstimatedCost += costOf(Math.max(0, inputTokens), Math.max(0, outputTokens));
    }

    public synchronized Snapshot snapshot() {
        rollover();
        return new Snapshot(
                lastResetDate,
                dailyRequestCount,
                dailyInputCharCount,
                dailyEstimatedInputTokens,
                dailyEstimatedOutputTokens,
                dailyEstimatedCost);
    }

    private double costOf(long inputTokens, long outputTokens) {
        return (inputTokens / 1000.0d) * limits.pricePer1kInputTokens()
                + (outputTokens / 1000.0d) * limits.pricePer1kOutputTokens();
    }

    private void rollover() {
        LocalDate today = clock.get();
        if (!today.equals(lastResetDate)) {
            lastResetDate = today;
            dailyRequestCount = 0;
            dailyInputCharCount = 0;
            dailyEstimatedInputTokens = 0;
            dailyEstimatedOutputTokens = 0;
            dailyEstimatedCost = 0d;
        }
    }
}
