package space.br1440.platform.tracing.bench.contract;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release-гейт производительности (Фаза 17, PR-6; ADR-performance-model, Решение 7).
 * <p>
 * Активируется ТОЛЬКО при {@code -Dplatform.release.gate=true} (Gradle-задача
 * {@code performanceReleaseGate}): обычный {@code test} его пропускает, потому что до
 * сбора evidence на reference-лаборатории hard-бюджеты находятся в {@code PENDING} —
 * это корректное pre-release состояние, которое НЕ должно ломать ежедневную сборку,
 * но ОБЯЗАНО блокировать релиз.
 * <p>
 * Гейт фейлится, если:
 * <ol>
 *   <li>любой hard-бюджет в {@code PENDING} (нет evidence);</li>
 *   <li>любой hard-бюджет в {@code WAIVER} без записи waiver'а (целостность waivers
 *       и непросроченность проверяет всегда-активный {@code PerformanceBudgetsContractTest}).</li>
 * </ol>
 * Warning/evidence-tier бюджеты релиз не блокируют (трёхуровневая модель гейтов).
 */
@EnabledIfSystemProperty(named = "platform.release.gate", matches = "true")
class PerformanceReleaseGateTest {

    private static final Path BUDGETS_YAML =
            Path.of("..", "docs", "tracing", "performance-budgets.yaml");

    private static Map<String, Object> root;

    @BeforeAll
    static void loadYaml() {
        try (InputStream in = Files.newInputStream(BUDGETS_YAML)) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + BUDGETS_YAML, e);
        }
    }

    @Test
    void все_hard_бюджеты_закрыты_evidence_или_waiver() {
        List<Map<String, Object>> pendingHard = budgets().stream()
                .filter(b -> "hard".equals(b.get("tier")))
                .filter(b -> "PENDING".equals(b.get("status")))
                .collect(Collectors.toList());

        assertThat(pendingHard)
                .as("Release заблокирован: hard-бюджеты без evidence (PENDING): %s. "
                                + "Выполните прогоны матрицы на reference-лаборатории "
                                + "(performance-test-matrix.md) и обновите performance-budgets.yaml, "
                                + "либо оформите waiver через board (expires/approvedBy/evidence).",
                        pendingHard.stream().map(b -> b.get("id")).collect(Collectors.toList()))
                .isEmpty();
    }

    @Test
    void hard_waiver_бюджеты_имеют_запись_в_waivers() {
        List<String> waiverIds = waivers().stream()
                .map(w -> (String) w.get("budgetId"))
                .collect(Collectors.toList());
        budgets().stream()
                .filter(b -> "hard".equals(b.get("tier")) && "WAIVER".equals(b.get("status")))
                .forEach(b -> assertThat(waiverIds)
                        .as("hard-бюджет %s в WAIVER без записи в waivers[]", b.get("id"))
                        .contains((String) b.get("id")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> budgets() {
        return (List<Map<String, Object>>) root.get("budgets");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> waivers() {
        Object waivers = root.get("waivers");
        return waivers == null ? List.of() : (List<Map<String, Object>>) waivers;
    }
}
