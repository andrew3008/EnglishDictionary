package space.br1440.platform.tracing.bench.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5.7 — committed budget contract must remain greppable in AcceptedStateBenchmarkFixtures.
 *
 * <p>Thresholds are not invented here; they are the BUDGET lines committed in the fixtures
 * class (plan §20 P5.7: otherwise {@code BLOCKED_BUDGET_MISSING}).
 */
class P57BudgetContractTest {

    private static final List<String> REQUIRED_BUDGETS = List.of(
            "BUDGET:read:0 B/op gc.alloc.rate.norm",
            "BUDGET:digest_no_routes:800 ns/op AverageTime JDK21",
            "BUDGET:digest_five_routes:1200 ns/op AverageTime JDK21",
            "BUDGET:apply_unchanged:250 ns/op AverageTime JDK21",
            "BUDGET:apply_applied:600 ns/op AverageTime JDK21",
            "BUDGET:encode_no_routes:15000 ns/op AverageTime JDK21",
            "BUDGET:encode_five_routes:20000 ns/op AverageTime JDK21",
            "BUDGET:decode_no_routes:20000 ns/op AverageTime JDK21",
            "BUDGET:decode_five_routes:25000 ns/op AverageTime JDK21"
    );

    @Test
    void fixturesDeclareAllP57BudgetLines() throws IOException {
        Path fixtures = Path.of(
                "src/jmh/java/space/br1440/platform/tracing/bench/acceptedstate",
                "AcceptedStateBenchmarkFixtures.java");
        assertThat(fixtures).exists();
        String text = Files.readString(fixtures, StandardCharsets.UTF_8);
        for (String budget : REQUIRED_BUDGETS) {
            assertThat(text)
                    .as("committed P5.7 budget line must be present: %s", budget)
                    .contains(budget);
        }
    }
}
