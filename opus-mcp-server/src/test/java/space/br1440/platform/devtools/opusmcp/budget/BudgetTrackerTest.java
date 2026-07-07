package space.br1440.platform.devtools.opusmcp.budget;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetTrackerTest {

    @Test
    void disabledLimitsAlwaysAllow() {
        BudgetTracker tracker = new BudgetTracker(BudgetTracker.BudgetLimits.disabled());
        for (int i = 0; i < 100; i++) {
            assertThat(tracker.preCheck(1000, 250).allowed()).isTrue();
            tracker.record(1000, 250, 250);
        }
    }

    @Test
    void dailyRequestLimitBlocks() {
        BudgetTracker tracker = new BudgetTracker(
                new BudgetTracker.BudgetLimits(2, 0, 0, 0d, 0d, 0d));
        assertThat(tracker.preCheck(10, 3).allowed()).isTrue();
        tracker.record(10, 3, 3);
        assertThat(tracker.preCheck(10, 3).allowed()).isTrue();
        tracker.record(10, 3, 3);
        BudgetTracker.BudgetDecision decision = tracker.preCheck(10, 3);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("request limit");
    }

    @Test
    void dailyInputCharLimitBlocks() {
        BudgetTracker tracker = new BudgetTracker(
                new BudgetTracker.BudgetLimits(0, 100, 0, 0d, 0d, 0d));
        assertThat(tracker.preCheck(80, 20).allowed()).isTrue();
        tracker.record(80, 20, 20);
        BudgetTracker.BudgetDecision decision = tracker.preCheck(50, 12);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("input character");
    }

    @Test
    void dailyTokenLimitBlocks() {
        BudgetTracker tracker = new BudgetTracker(
                new BudgetTracker.BudgetLimits(0, 0, 100, 0d, 0d, 0d));
        tracker.record(10, 90, 10);
        assertThat(tracker.preCheck(10, 20).allowed()).isFalse();
    }

    @Test
    void countersResetOnNewDay() {
        AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.of(2026, 1, 1));
        BudgetTracker tracker = new BudgetTracker(
                new BudgetTracker.BudgetLimits(1, 0, 0, 0d, 0d, 0d), day::get);
        tracker.record(10, 5, 5);
        assertThat(tracker.preCheck(1, 1).allowed()).isFalse();

        day.set(LocalDate.of(2026, 1, 2));
        assertThat(tracker.preCheck(1, 1).allowed()).isTrue();
        assertThat(tracker.snapshot().requestCount()).isZero();
    }

    @Test
    void recordsCostFromPricing() {
        BudgetTracker tracker = new BudgetTracker(
                new BudgetTracker.BudgetLimits(0, 0, 0, 0d, 2.0d, 4.0d));
        tracker.record(0, 1000, 1000);
        assertThat(tracker.snapshot().estimatedCost()).isEqualTo(6.0d);
        assertThat(tracker.snapshot().estimatedInputTokens()).isEqualTo(1000);
        assertThat(tracker.snapshot().estimatedOutputTokens()).isEqualTo(1000);
    }
}
