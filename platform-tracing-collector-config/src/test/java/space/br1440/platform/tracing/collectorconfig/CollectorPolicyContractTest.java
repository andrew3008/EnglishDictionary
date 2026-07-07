package space.br1440.platform.tracing.collectorconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт SDK ↔ Collector (Фаза 16, ADR-collector-boundary).
 * <p>
 * YAML-политики Collector'а и Java-константы SDK живут в разных мирах — этот тест
 * машинно гарантирует их синхронизацию (P0-баг «x_trace_on» Фазы 16 был именно
 * рассинхронизацией: production-политика ссылалась на значение, которое SDK
 * никогда не эмитил; доказан эмпирически Spike S3 на Gentoo).
 * <p>
 * Проверяется:
 * <ol>
 *   <li>{@code string_attribute}-политики ссылаются только на известные ключи атрибутов;</li>
 *   <li>значения политик по {@code platform.sampling.reason} входят в
 *       {@link PlatformSamplingReasons#EXPORTED} (DROP-значения Collector не видит);</li>
 *   <li>порядок процессоров: {@code memory_limiter} первый, {@code batch} последний,
 *       transform-backstop и redaction — до {@code tail_sampling};</li>
 *   <li>routing connector маршрутизирует только по resource-атрибутам
 *       (анти-фрагментация, вердикт Spike S1: {@code context: span} расщепляет трейс);</li>
 *   <li>e2e-конфиг не дрейфует от production (policy-типы e2e ⊆ production).</li>
 * </ol>
 * Тест JVM-only (snakeyaml), Docker не требуется — работает в обычном CI.
 * Намеренно НЕ проверяются проценты/пороги/размеры — они env-tunable зоны SRE.
 */
class CollectorPolicyContractTest {

    private static final String GATEWAY_YAML =
            "platform-tracing/collector/otel-collector-gateway-tail-sampling.yaml";
    private static final String AGENT_YAML =
            "platform-tracing/collector/otel-collector-agent-loadbalancing.yaml";
    private static final String TTL_TIERS_YAML =
            "platform-tracing/collector/otel-collector-config-ttl-tiers.yaml";
    /** E2e-конфиг живёт в test-resources соседнего модуля — читаем по относительному пути. */
    private static final Path E2E_YAML = Path.of(
            "..", "platform-tracing-e2e-tests", "src", "test", "resources", "e2e",
            "otel-collector-e2e.yaml");

    /** Ключи span-атрибутов, на которые разрешено ссылаться string_attribute-политикам. */
    private static final Set<String> ALLOWED_POLICY_KEYS = Set.of(
            PlatformAttributes.PLATFORM_SAMPLING_REASON,
            PlatformAttributes.PLATFORM_TRACE_PRIORITY,
            // OTel semconv: маршрут для drop-политики health-check'ов.
            "http.route");

    // -- 1. Ключи политик -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {GATEWAY_YAML, TTL_TIERS_YAML})
    void string_attribute_политики_ссылаются_только_на_известные_ключи(String resource) {
        Map<String, Object> config = loadClasspathYaml(resource);
        for (Map<String, Object> stringAttribute : collectStringAttributeBlocks(config)) {
            assertThat((String) stringAttribute.get("key"))
                    .as("string_attribute.key в %s должен входить в разрешённое множество", resource)
                    .isIn(ALLOWED_POLICY_KEYS);
        }
    }

    @Test
    void string_attribute_политики_e2e_ссылаются_только_на_известные_ключи() {
        Map<String, Object> config = loadFileYaml(E2E_YAML);
        for (Map<String, Object> stringAttribute : collectStringAttributeBlocks(config)) {
            assertThat((String) stringAttribute.get("key")).isIn(ALLOWED_POLICY_KEYS);
        }
    }

    // -- 2. Значения platform.sampling.reason ----------------------------------------------

    @Test
    void forced_traces_values_подмножество_экспортируемых_reason_значений() {
        Map<String, Object> policy = findPolicy(loadClasspathYaml(GATEWAY_YAML), "forced-traces")
                .orElseThrow(() -> new AssertionError("Политика forced-traces не найдена в gateway YAML"));
        List<String> values = stringAttributeValues(policy);
        // Ловит дрейф вида x_trace_on: SDK эмитит только EXPORTED-значения.
        assertThat(PlatformSamplingReasons.EXPORTED)
                .as("forced-traces.values обязаны входить в PlatformSamplingReasons.EXPORTED")
                .containsAll(values);
        // Минимальная гарантия требования «X-Trace-On → 100% retention» (Traces Requests §3).
        assertThat(values).contains(PlatformSamplingReasons.FORCE_HEADER);
        // Решение архитектурного ревью Фазы 16: parent_sampled НЕ входит в forced-policy,
        // иначе она вырождается в keep-all для всего sampled-трафика.
        assertThat(values).doesNotContain(PlatformSamplingReasons.PARENT_SAMPLED);
    }

    @Test
    void e2e_forced_record_values_подмножество_экспортируемых_reason_значений() {
        Map<String, Object> policy = findPolicy(loadFileYaml(E2E_YAML), "forced-record")
                .orElseThrow(() -> new AssertionError("Политика forced-record не найдена в e2e YAML"));
        List<String> values = stringAttributeValues(policy);
        assertThat(PlatformSamplingReasons.EXPORTED).containsAll(values);
        assertThat(values).contains(PlatformSamplingReasons.FORCE_HEADER);
    }

    @Test
    void reason_политики_не_ссылаются_на_drop_значения() {
        // DROP-значения (drop_path, parent_drop, ...) не экспортируются — политика
        // по ним мертва by construction.
        for (String resource : List.of(GATEWAY_YAML, TTL_TIERS_YAML)) {
            Map<String, Object> config = loadClasspathYaml(resource);
            for (Map<String, Object> stringAttribute : collectStringAttributeBlocks(config)) {
                if (PlatformAttributes.PLATFORM_SAMPLING_REASON.equals(stringAttribute.get("key"))) {
                    List<String> values = asStringList(stringAttribute.get("values"));
                    assertThat(values)
                            .as("%s: политика по platform.sampling.reason содержит DROP-значения", resource)
                            .doesNotContainAnyElementsOf(PlatformSamplingReasons.DROPPED);
                }
            }
        }
    }

    // -- 3. Порядок процессоров -------------------------------------------------------------

    @Test
    void порядок_процессоров_gateway() {
        List<String> processors = pipelineProcessors(loadClasspathYaml(GATEWAY_YAML), "traces");
        assertThat(processors).first().isEqualTo("memory_limiter");
        assertThat(processors).last().isEqualTo("batch");
        int transform = processors.indexOf("transform/platform-semconv-backstop");
        int redaction = processors.indexOf("redaction/platform-second-line");
        int tailSampling = processors.indexOf("tail_sampling");
        assertThat(transform)
                .as("transform backstop обязан стоять до tail_sampling")
                .isNotNegative().isLessThan(tailSampling);
        assertThat(redaction)
                .as("redaction (2-я линия защиты) обязан стоять между transform и tail_sampling")
                .isGreaterThan(transform).isLessThan(tailSampling);
    }

    @Test
    void порядок_процессоров_agent() {
        List<String> processors = pipelineProcessors(loadClasspathYaml(AGENT_YAML), "traces");
        assertThat(processors).first().isEqualTo("memory_limiter");
        assertThat(processors).last().isEqualTo("batch");
        // k8sattributes — context-зависимое обогащение, до batch (ADR-resource-merge-precedence).
        assertThat(processors.indexOf("k8sattributes"))
                .isGreaterThan(processors.indexOf("memory_limiter"))
                .isLessThan(processors.indexOf("batch"));
    }

    @Test
    void порядок_процессоров_ttl_tiers_входной_пайплайн() {
        Map<String, Object> config = loadClasspathYaml(TTL_TIERS_YAML);
        List<String> in = pipelineProcessors(config, "traces/in");
        assertThat(in).first().isEqualTo("memory_limiter");
        // transform-эскалация на resource — после tail_sampling-решения, до routing connector.
        assertThat(in.indexOf("transform/copy-result-to-resource"))
                .isGreaterThan(in.indexOf("tail_sampling"));
        // Целевые пайплайны: batch последним перед exporter'ами.
        assertThat(pipelineProcessors(config, "traces/long")).last().isEqualTo("batch");
        assertThat(pipelineProcessors(config, "traces/short")).last().isEqualTo("batch");
    }

    // -- 4. Routing connector: анти-фрагментация (Spike S1) ---------------------------------

    @Test
    void routing_connector_маршрутизирует_только_по_resource_атрибутам() {
        Map<String, Object> config = loadClasspathYaml(TTL_TIERS_YAML);

        // Deprecated routing PROCESSOR (невалиден на пине 0.154.0) запрещён.
        Map<String, Object> processors = section(config, "processors");
        assertThat(processors.keySet())
                .as("routing processor deprecated и невалиден на пине contrib — только connector")
                .noneMatch(name -> name.equals("routing") || name.startsWith("routing/"));

        Map<String, Object> connectors = section(config, "connectors");
        Map<String, Object> routing = castMap(connectors.get("routing"));
        assertThat(routing).as("routing connector обязан присутствовать в ttl-tiers").isNotNull();

        // match_once удалён с contrib 0.120 — наличие поля ломает validate.
        assertThat(routing).doesNotContainKey("match_once");

        List<Object> table = asList(routing.get("table"));
        assertThat(table).isNotEmpty();
        for (Object rowObj : table) {
            Map<String, Object> row = castMap(rowObj);
            // Вердикт Spike S1: context: span фрагментирует multi-span трейс между
            // backend'ами — маршрутизация допустима только на уровне resource.
            assertThat(row.get("context"))
                    .as("routing.table[].context обязан быть resource (анти-фрагментация, Spike S1)")
                    .isEqualTo("resource");
            assertThat((String) row.get("condition"))
                    .as("условие routing'а обязано ссылаться на resource.attributes")
                    .contains("resource.attributes[");
        }
    }

    // -- 5. Дрейф e2e ↔ production ----------------------------------------------------------

    @Test
    void e2e_policy_типы_согласованы_с_production() {
        Set<String> productionTypes = policyTypes(loadClasspathYaml(GATEWAY_YAML));
        Set<String> e2eTypes = policyTypes(loadFileYaml(E2E_YAML));
        // e2e — детерминированное подмножество production-семантики; появление в e2e
        // policy-типа, отсутствующего в production, означает дрейф конфигов.
        assertThat(productionTypes)
                .as("policy-типы e2e обязаны быть подмножеством production")
                .containsAll(e2eTypes);
    }

    @Test
    void e2e_и_production_политики_reason_используют_одинаковый_ключ() {
        Map<String, Object> production = findPolicy(loadClasspathYaml(GATEWAY_YAML), "forced-traces").orElseThrow();
        Map<String, Object> e2e = findPolicy(loadFileYaml(E2E_YAML), "forced-record").orElseThrow();
        assertThat(stringAttributeKey(e2e)).isEqualTo(stringAttributeKey(production))
                .isEqualTo(PlatformAttributes.PLATFORM_SAMPLING_REASON);
    }

    // -- helpers ------------------------------------------------------------------------------

    private static Map<String, Object> loadClasspathYaml(String resource) {
        try (InputStream in = CollectorPolicyContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as("ресурс %s должен присутствовать на classpath", resource).isNotNull();
            return castMap(newYaml().load(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> loadFileYaml(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return castMap(newYaml().load(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + path.toAbsolutePath(), e);
        }
    }

    private static Yaml newYaml() {
        // SafeConstructor: конфиги — недоверенный ввод с точки зрения десериализации.
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    /** Все блоки string_attribute (включая вложенные в композитные drop/and/not политики). */
    private static List<Map<String, Object>> collectStringAttributeBlocks(Object node) {
        List<Map<String, Object>> result = new ArrayList<>();
        collectStringAttributeBlocks(node, result);
        return result;
    }

    private static void collectStringAttributeBlocks(Object node, List<Map<String, Object>> result) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("string_attribute".equals(entry.getKey()) && entry.getValue() instanceof Map) {
                    result.add(castMap(entry.getValue()));
                } else {
                    collectStringAttributeBlocks(entry.getValue(), result);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectStringAttributeBlocks(item, result);
            }
        }
    }

    private static Optional<Map<String, Object>> findPolicy(Map<String, Object> config, String name) {
        Map<String, Object> processors = section(config, "processors");
        Map<String, Object> tailSampling = castMap(processors.get("tail_sampling"));
        if (tailSampling == null) {
            return Optional.empty();
        }
        for (Object policyObj : asList(tailSampling.get("policies"))) {
            Map<String, Object> policy = castMap(policyObj);
            if (name.equals(policy.get("name"))) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    private static Set<String> policyTypes(Map<String, Object> config) {
        Map<String, Object> tailSampling = castMap(section(config, "processors").get("tail_sampling"));
        assertThat(tailSampling).isNotNull();
        List<Object> policies = asList(tailSampling.get("policies"));
        Set<String> types = new java.util.HashSet<>();
        for (Object policyObj : policies) {
            types.add((String) castMap(policyObj).get("type"));
        }
        return types;
    }

    private static List<String> pipelineProcessors(Map<String, Object> config, String pipeline) {
        Map<String, Object> service = section(config, "service");
        Map<String, Object> pipelines = castMap(service.get("pipelines"));
        Map<String, Object> tracesPipeline = castMap(pipelines.get(pipeline));
        assertThat(tracesPipeline).as("pipeline %s должен существовать", pipeline).isNotNull();
        return asStringList(tracesPipeline.get("processors"));
    }

    private static String stringAttributeKey(Map<String, Object> policy) {
        return (String) castMap(policy.get("string_attribute")).get("key");
    }

    private static List<String> stringAttributeValues(Map<String, Object> policy) {
        return asStringList(castMap(policy.get("string_attribute")).get("values"));
    }

    private static Map<String, Object> section(Map<String, Object> config, String name) {
        Map<String, Object> section = castMap(config.get(name));
        assertThat(section).as("секция %s должна существовать", name).isNotNull();
        return section;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }
}
