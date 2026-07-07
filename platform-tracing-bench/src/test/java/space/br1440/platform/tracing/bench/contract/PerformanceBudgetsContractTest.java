package space.br1440.platform.tracing.bench.contract;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт performance-budgets.yaml (Фаза 17, ADR-performance-model, Решение 6).
 * <p>
 * Бюджеты производительности — версионируемые данные (policy-файл), а не Java-код.
 * Этот тест машинно гарантирует целостность governance-контракта:
 * <ol>
 *   <li>обязательные поля каждой записи {@code budgets[]} присутствуют;</li>
 *   <li>{@code status} принимает только {@code PASS|WAIVER|PENDING};</li>
 *   <li>{@code tier} принимает только {@code hard|warning|evidence};</li>
 *   <li>каждый бюджет со статусом {@code WAIVER} имеет запись в {@code waivers[]}
 *       с непросроченной датой {@code expires} и полями {@code approvedBy}/{@code evidence};</li>
 *   <li>{@code requirement} каждого бюджета существует как REQ-идентификатор
 *       в requirements-traceability.md (защита от дрейфа документов);</li>
 *   <li>у {@code PASS}-бюджетов заполнена ссылка {@code evidence};</li>
 *   <li>{@code referenceLab} содержит шумовой порог и статпротокол.</li>
 * </ol>
 * Тест JVM-only (snakeyaml), паттерн — {@code CollectorPolicyContractTest} Фазы 16.
 * Намеренно НЕ проверяются численные значения бюджетов — они зона ADR/board.
 */
class PerformanceBudgetsContractTest {

    /** Policy-файл живёт в docs/ корня репозитория — читаем по относительному пути. */
    private static final Path BUDGETS_YAML =
            Path.of("..", "docs", "tracing", "performance-budgets.yaml");
    private static final Path TRACEABILITY_MD =
            Path.of("..", "docs", "tracing", "requirements-traceability.md");

    private static final Set<String> ALLOWED_STATUSES = Set.of("PASS", "WAIVER", "PENDING");
    private static final Set<String> ALLOWED_TIERS = Set.of("hard", "warning", "evidence");
    private static final Pattern REQ_ID_PATTERN = Pattern.compile("REQ-[A-Z0-9-]+");

    private static Map<String, Object> root;

    @BeforeAll
    static void loadYaml() {
        root = loadFileYaml(BUDGETS_YAML);
    }

    // -- 1–3. Схема записей budgets[] ------------------------------------------------------

    @Test
    void каждый_бюджет_содержит_обязательные_поля() {
        for (Map<String, Object> budget : budgets()) {
            assertThat(budget)
                    .as("запись budgets[] обязана содержать поля контракта: %s", budget)
                    .containsKeys("id", "requirement", "scenario", "metric",
                            "tier", "owner", "status");
            // budget может быть null (evidence-tier до первого замера), но ключ обязан существовать.
            assertThat(budget.containsKey("budget"))
                    .as("ключ 'budget' обязателен (null допустим только для evidence-tier): %s",
                            budget.get("id"))
                    .isTrue();
        }
    }

    @Test
    void status_только_из_разрешённого_множества() {
        for (Map<String, Object> budget : budgets()) {
            assertThat((String) budget.get("status"))
                    .as("status бюджета %s", budget.get("id"))
                    .isIn(ALLOWED_STATUSES);
        }
    }

    @Test
    void tier_только_из_разрешённого_множества() {
        for (Map<String, Object> budget : budgets()) {
            assertThat((String) budget.get("tier"))
                    .as("tier бюджета %s", budget.get("id"))
                    .isIn(ALLOWED_TIERS);
        }
    }

    @Test
    void идентификаторы_бюджетов_уникальны() {
        List<String> ids = budgets().stream()
                .map(b -> (String) b.get("id"))
                .toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void hard_и_warning_бюджеты_имеют_числовое_значение() {
        for (Map<String, Object> budget : budgets()) {
            String tier = (String) budget.get("tier");
            if ("hard".equals(tier) || "warning".equals(tier)) {
                assertThat(budget.get("budget"))
                        .as("бюджет %s (tier=%s) обязан иметь числовое значение",
                                budget.get("id"), tier)
                        .isInstanceOf(Number.class);
            }
        }
    }

    // -- 4. Waiver governance ---------------------------------------------------------------

    @Test
    void каждый_waiver_бюджет_имеет_непросроченную_запись_в_waivers() {
        Map<String, Map<String, Object>> waiversByBudget = waivers().stream()
                .collect(Collectors.toMap(w -> (String) w.get("budgetId"), w -> w));

        for (Map<String, Object> budget : budgets()) {
            if (!"WAIVER".equals(budget.get("status"))) {
                continue;
            }
            String id = (String) budget.get("id");
            Map<String, Object> waiver = waiversByBudget.get(id);
            assertThat(waiver)
                    .as("бюджет %s со статусом WAIVER обязан иметь запись в waivers[]", id)
                    .isNotNull();
            assertThat(waiver)
                    .as("waiver для %s обязан содержать reason/expires/approvedBy/evidence", id)
                    .containsKeys("reason", "expires", "approvedBy", "evidence");
            assertThat(parseDate(waiver.get("expires")))
                    .as("waiver для %s просрочен — продлите через board или закройте бюджет", id)
                    .isAfterOrEqualTo(LocalDate.now());
        }
    }

    @Test
    void каждая_запись_waivers_ссылается_на_существующий_бюджет() {
        Set<String> budgetIds = budgets().stream()
                .map(b -> (String) b.get("id"))
                .collect(Collectors.toSet());
        for (Map<String, Object> waiver : waivers()) {
            assertThat((String) waiver.get("budgetId"))
                    .as("waivers[].budgetId должен ссылаться на существующий бюджет")
                    .isIn(budgetIds);
        }
    }

    // -- 5. Дрейф с requirements-traceability.md ---------------------------------------------

    @Test
    void requirement_каждого_бюджета_существует_в_traceability_матрице() {
        Set<String> knownRequirements = requirementIdsFromTraceability();
        assertThat(knownRequirements)
                .as("requirements-traceability.md должен содержать REQ-идентификаторы")
                .isNotEmpty();
        for (Map<String, Object> budget : budgets()) {
            String requirement = (String) budget.get("requirement");
            // Составные ссылки вида "REQ-KAFKA-001 / REQ-DB-001" недопустимы в budgets —
            // каждая запись бюджета привязана ровно к одному требованию.
            assertThat(requirement)
                    .as("requirement бюджета %s", budget.get("id"))
                    .matches(REQ_ID_PATTERN.pattern());
            assertThat(knownRequirements)
                    .as("requirement %s бюджета %s отсутствует в requirements-traceability.md",
                            requirement, budget.get("id"))
                    .contains(requirement);
        }
    }

    // -- 6. PASS требует evidence -------------------------------------------------------------

    @Test
    void pass_бюджеты_имеют_evidence_ссылку() {
        for (Map<String, Object> budget : budgets()) {
            if ("PASS".equals(budget.get("status"))) {
                assertThat(budget.get("evidence"))
                        .as("бюджет %s со статусом PASS обязан ссылаться на measurement-артефакт",
                                budget.get("id"))
                        .isNotNull();
            }
        }
    }

    // -- 7. referenceLab -----------------------------------------------------------------------

    @Test
    void referenceLab_содержит_статпротокол() {
        @SuppressWarnings("unchecked")
        Map<String, Object> lab = (Map<String, Object>) root.get("referenceLab");
        assertThat(lab)
                .as("referenceLab обязателен (ADR-performance-model, Решение 5/6)")
                .isNotNull()
                .containsKeys("hostId", "cpuNoiseMaxPct", "steadyStateMin",
                        "runsPerConfig", "hardwareFingerprint");
    }

    // -- Вспомогательные методы ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> budgets() {
        Object budgets = root.get("budgets");
        assertThat(budgets).as("секция budgets[] обязательна").isNotNull();
        return (List<Map<String, Object>>) budgets;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> waivers() {
        Object waivers = root.get("waivers");
        return waivers == null ? List.of() : (List<Map<String, Object>>) waivers;
    }

    private static Set<String> requirementIdsFromTraceability() {
        try {
            String content = Files.readString(TRACEABILITY_MD);
            Matcher matcher = REQ_ID_PATTERN.matcher(content);
            return matcher.results()
                    .map(r -> r.group())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + TRACEABILITY_MD, e);
        }
    }

    private static LocalDate parseDate(Object value) {
        // SnakeYAML SafeConstructor парсит ISO-даты в java.util.Date; строки оставляет строками.
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static Map<String, Object> loadFileYaml(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + path, e);
        }
    }
}
