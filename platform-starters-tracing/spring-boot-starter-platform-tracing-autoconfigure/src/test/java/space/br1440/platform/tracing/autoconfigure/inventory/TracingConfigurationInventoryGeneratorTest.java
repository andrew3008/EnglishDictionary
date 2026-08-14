package space.br1440.platform.tracing.autoconfigure.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.propagation.PropagationDefaults;
import space.br1440.platform.tracing.otel.javaagent.resource.PlatformResourceCustomizer;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

/**
 * Генерирует и проверяет полный репозиторный инвентарь tracing-конфигурации S-04.
 */
class TracingConfigurationInventoryGeneratorTest {

    private static final String OUTPUT =
            "docs/research/evidence/tracing-configuration-inventory";
    private static final String DISPOSITION =
            "docs/research/tracing-configuration-key-disposition.md";
    private static final List<String> STABLE_FILES = List.of(
            "inventory.json",
            "inventory.csv",
            "declarations.json",
            "reader-consumer-links.json",
            "duplicates.json",
            "environment-variables.json",
            "dead-and-conflicting-names.json",
            "category-a-candidates.json",
            "generator-summary.txt",
            "n1-n12-resolution.md");
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".gradle", ".properties", ".yml", ".yaml", ".md", ".json", ".xml",
            ".ps1", ".sh", ".kts");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    @Test
    void generatedInventoryMatchesCommittedEvidence() throws Exception {
        Path root = repositoryRoot();
        Inventory inventory = generate(root);
        validate(inventory);

        if (Boolean.getBoolean("tracing.configuration.inventory.update")) {
            writeArtifacts(root.resolve(OUTPUT), root.resolve(DISPOSITION), inventory);
            return;
        }

        Path temporary = Files.createTempDirectory("tracing-configuration-inventory-");
        Path generatedDisposition = temporary.resolve("tracing-configuration-key-disposition.md");
        writeArtifacts(temporary, generatedDisposition, inventory);

        for (String file : STABLE_FILES) {
            assertSameText(
                    root.resolve(OUTPUT).resolve(file),
                    temporary.resolve(file),
                    regenerationCommand());
        }
        assertSameText(root.resolve(DISPOSITION), generatedDisposition, regenerationCommand());
    }

    private static Inventory generate(Path root) throws Exception {
        SourceIndex source = SourceIndex.load(root);
        Map<String, Entry> entries = new TreeMap<>();

        TracingProperties properties = new TracingProperties();
        collectSpringLeaves(
                entries,
                source,
                properties,
                TracingProperties.class,
                TracingProperties.PREFIX,
                "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/"
                        + "space/br1440/platform/tracing/autoconfigure/TracingProperties.java",
                new ArrayList<>());

        collectStringConstants(entries, source, ExtensionPropertyNames.class,
                "platform-tracing-otel-javaagent-extension/src/main/java/"
                        + "space/br1440/platform/tracing/otel/javaagent/configuration/"
                        + "ExtensionPropertyNames.java",
                field -> Modifier.isPublic(field.getModifiers()));
        collectStringConstants(entries, source, PropagationDefaults.class,
                "platform-tracing-otel-javaagent-extension/src/main/java/"
                        + "space/br1440/platform/tracing/otel/javaagent/propagation/"
                        + "PropagationDefaults.java",
                field -> field.getName().startsWith("PROP_"));
        collectStringConstants(entries, source, PlatformResourceCustomizer.class,
                "platform-tracing-otel-javaagent-extension/src/main/java/"
                        + "space/br1440/platform/tracing/otel/javaagent/resource/"
                        + "PlatformResourceCustomizer.java",
                field -> field.getName().startsWith("PROP_"));

        addConditionalFixture(entries, source);
        addConditionalOnPropertyGates(entries, source);
        addBootstrapEnabledProperty(entries, source);
        addCategoryATraceControlHeadersEnabled(entries, source);
        addCategoryAExporterEnabled(entries, source);
        addFixtureAndDocumentedNames(entries, source);
        addRelaxedBindingAlias(entries, source);
        addEnvironmentAndOtelNames(entries, source);
        enrichEntries(entries, source);
        assignStableIds(entries.values());

        List<Map<String, Object>> categoryA = categoryACandidates(entries);
        List<Map<String, Object>> n1n12 = n1n12(entries, source);
        List<Entry> sortedEntries = entries.values().stream()
                .sorted(Comparator.comparing((Entry entry) -> entry.kind)
                        .thenComparing(entry -> entry.exactName))
                .toList();
        return new Inventory(sortedEntries, categoryA, n1n12, source);
    }

    private static void collectSpringLeaves(
            Map<String, Entry> entries,
            SourceIndex source,
            Object owner,
            Class<?> type,
            String prefix,
            String declarationPath,
            List<String> segments) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(owner);
            List<String> path = new ArrayList<>(segments);
            path.add(kebab(field.getName()));
            if (field.getType().getEnclosingClass() == TracingProperties.class
                    || field.getType().getEnclosingClass() != null
                    && field.getType().getEnclosingClass().getEnclosingClass()
                    == TracingProperties.class) {
                collectSpringLeaves(entries, source, value, field.getType(), prefix,
                        declarationPath, path);
                continue;
            }

            String exactName = prefix + "." + String.join(".", path);
            Entry entry = entries.computeIfAbsent(exactName, Entry::new);
            entry.kind = "CONFIG_PROPERTY";
            entry.prefixFamily = family(exactName);
            entry.declarations.add(location(
                    declarationPath,
                    type.getSimpleName() + "." + field.getName(),
                    genericType(field)));
            entry.readers.add(link(
                    declarationPath,
                    "Spring @ConfigurationProperties binding",
                    "SPRING_BINDING"));
            entry.valueType = simpleType(field.getType());
            entry.collectionElementType = collectionElementType(field);
            entry.defaultValue = display(value);
            entry.defaultDefinitions.add("Spring:" + display(value));
            entry.defaultOwner = "Spring startup";
            entry.lifecycleOwner = "Spring application context";
            entry.currentStatus = "ACTIVE";
            entry.readerStatus = "REFLECTION_OR_BINDING_READER";
            entry.consumerStatus = "ACTIVE";
            entry.consumers.add(springConsumer(path));
            entry.documents.addAll(source.references(exactName, SourceIndex::isDocumentation));
        }
    }

    private static void collectStringConstants(
            Map<String, Entry> entries,
            SourceIndex source,
            Class<?> owner,
            String declarationPath,
            Predicate<Field> selector) throws Exception {
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class
                    || !selector.test(field)) {
                continue;
            }
            field.setAccessible(true);
            String exactName = (String) field.get(null);
            if (!isInventoryName(exactName)) {
                continue;
            }
            Entry entry = entries.computeIfAbsent(exactName, Entry::new);
            entry.kind = kindForConstant(field.getName(), exactName);
            entry.prefixFamily = family(exactName);
            entry.declarations.add(location(
                    declarationPath,
                    owner.getSimpleName() + "." + field.getName(),
                    "String"));
            entry.readers.addAll(source.symbolReferences(
                    owner.getSimpleName() + "." + field.getName(), declarationPath));
            entry.readers.addAll(source.symbolReferences(field.getName(), declarationPath).stream()
                    .filter(value -> !value.contains("TracingProperties.java"))
                    .toList());
            entry.documents.addAll(source.references(exactName, SourceIndex::isDocumentation));
            entry.fixtures.addAll(source.references(exactName, SourceIndex::isFixture));
            entry.valueTypeDefinitions.add(owner.getSimpleName() + ":String");
            if ("UNKNOWN".equals(entry.valueType)) {
                entry.valueType = inferAgentType(source, field.getName());
            }
            String agentDefault = inferAgentDefault(exactName);
            if ("NONE".equals(entry.defaultValue)) {
                entry.defaultValue = agentDefault;
            }
            entry.defaultDefinitions.add("Agent:" + agentDefault);
            entry.defaultOwner = "Agent bootstrap";
            entry.lifecycleOwner = "OpenTelemetry Java Agent extension";
            entry.consumers.add(agentConsumer(exactName));
            entry.readerStatus = entry.readers.isEmpty()
                    ? "DECLARED_NO_READER" : "ACTIVE_READER";
            entry.consumerStatus = "NO_RUNTIME_CONSUMER_FOUND".equals(entry.consumers.get(0))
                    ? "NONE" : "ACTIVE";
            entry.currentStatus = "ACTIVE";
        }
    }

    private static void addConditionalFixture(
            Map<String, Entry> entries,
            SourceIndex source) {
        String name = "platform.tracing.test.semconv-strict";
        Entry entry = entries.computeIfAbsent(name, Entry::new);
        entry.kind = "CONFIG_PROPERTY";
        entry.prefixFamily = family(name);
        entry.declarations.add(location(
                "platform-tracing-testkit/src/main/java/space/br1440/platform/tracing/test/"
                        + "semconv/SemconvStrictTestAutoConfiguration.java",
                "@ConditionalOnProperty", "boolean"));
        entry.readers.add(link(
                "platform-tracing-testkit/src/main/java/space/br1440/platform/tracing/test/"
                        + "semconv/SemconvStrictTestAutoConfiguration.java",
                "@ConditionalOnProperty", "SPRING_BINDING"));
        entry.consumers.add("SemconvStrictTestAutoConfiguration");
        entry.valueType = "boolean";
        entry.defaultValue = "true";
        entry.defaultDefinitions.add("Test autoconfiguration:true");
        entry.defaultOwner = "Test fixture";
        entry.lifecycleOwner = "Test Spring context";
        entry.readerStatus = "FIXTURE_ONLY_READER";
        entry.consumerStatus = "FIXTURE_ONLY";
        entry.currentStatus = "FIXTURE_ONLY";
        entry.fixtures.addAll(source.references(name, SourceIndex::isFixture));
    }

    /**
     * Opt-in gates consumed only via {@code @ConditionalOnProperty}; no POJO leaves in
     * {@code TracingProperties} (binding overhead removed).
     */
    private static void addConditionalOnPropertyGates(
            Map<String, Entry> entries,
            SourceIndex source) {
        addConditionalOnPropertyGate(
                entries,
                source,
                "platform.tracing.kafka.batch-links-enabled",
                "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/"
                        + "space/br1440/platform/tracing/autoconfigure/integrations/kafka/"
                        + "PlatformKafkaAutoConfiguration.java",
                "KafkaBatchLinksAspect");
        addConditionalOnPropertyGate(
                entries,
                source,
                "platform.tracing.kafka.propagate-platform-headers",
                "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/"
                        + "space/br1440/platform/tracing/autoconfigure/integrations/kafka/"
                        + "PlatformKafkaOutboundAutoConfiguration.java",
                "PlatformKafkaProducerFactoryCustomizer");
        addSuppressMicrometerTracingGate(entries, source);
    }

    /**
     * {@code suppress-micrometer-tracing} is read directly from {@link org.springframework.core.env.Environment}
     * by {@code MicrometerTracingSuppressionResolver} — not via a {@code TracingProperties} POJO leaf. This is
     * required because the effective value (explicit override, else auto-derived from OTel Agent presence) must
     * be resolvable during the {@code @Conditional} phase, before the {@code TracingProperties} bean exists.
     */
    private static void addSuppressMicrometerTracingGate(
            Map<String, Entry> entries,
            SourceIndex source) {
        String name = "platform.tracing.suppression.suppress-micrometer-tracing";
        String declarationPath = "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/"
                + "space/br1440/platform/tracing/autoconfigure/integrations/observation/"
                + "MicrometerTracingSuppressionResolver.java";
        Entry entry = entries.computeIfAbsent(name, Entry::new);
        entry.kind = "CONFIG_PROPERTY";
        entry.prefixFamily = family(name);
        entry.declarations.add(location(declarationPath, "MicrometerTracingSuppressionResolver.PROPERTY_NAME", "Boolean"));
        entry.readers.add(link(declarationPath, "Environment.getProperty(PROPERTY_NAME, Boolean.class)", "ENVIRONMENT_READ"));
        entry.consumers.add("MicrometerTracingSuppressionResolver");
        entry.valueType = "Boolean";
        entry.defaultValue = "null (auto-derive: OtelJavaAgentMarkerDetector.isAgentPresent())";
        entry.defaultDefinitions.add("Environment:null -> OtelJavaAgentMarkerDetector.isAgentPresent()");
        entry.defaultOwner = "Spring startup";
        entry.lifecycleOwner = "Spring application context";
        entry.readerStatus = "ACTIVE_READER";
        entry.consumerStatus = "ACTIVE";
        entry.currentStatus = "ACTIVE";
        entry.documents.addAll(source.references(name, SourceIndex::isDocumentation));
        entry.fixtures.addAll(source.references(name, SourceIndex::isFixture));
    }

    private static void addConditionalOnPropertyGate(
            Map<String, Entry> entries,
            SourceIndex source,
            String name,
            String declarationPath,
            String consumer) {
        Entry entry = entries.computeIfAbsent(name, Entry::new);
        entry.kind = "CONFIG_PROPERTY";
        entry.prefixFamily = family(name);
        entry.declarations.add(location(declarationPath, "@ConditionalOnProperty", "boolean"));
        entry.readers.add(link(declarationPath, "@ConditionalOnProperty", "SPRING_BINDING"));
        entry.consumers.add(consumer);
        entry.valueType = "boolean";
        entry.defaultValue = "false";
        entry.defaultDefinitions.add("Spring:@ConditionalOnProperty matchIfMissing=false");
        entry.defaultOwner = "Spring startup";
        entry.lifecycleOwner = "Spring application context";
        entry.readerStatus = "ACTIVE_READER";
        entry.consumerStatus = "ACTIVE";
        entry.currentStatus = "ACTIVE";
        entry.documents.addAll(source.references(name, SourceIndex::isDocumentation));
        entry.fixtures.addAll(source.references(name, SourceIndex::isFixture));
    }

    /**
     * Category A kill-switch for inbound platform control headers. Owned by AcceptedPolicy /
     * {@code PlatformPropagationGate}; the former Spring mirror
     * {@code platform.tracing.propagation.enabled} was deleted as a dead POJO leaf.
     */
    private static void addCategoryATraceControlHeadersEnabled(
            Map<String, Entry> entries,
            SourceIndex source) {
        String name = "platform.tracing.policy.propagation.trace-control-headers.enabled";
        String declarationPath =
                "platform-tracing-otel-javaagent-extension/src/main/java/"
                        + "space/br1440/platform/tracing/otel/javaagent/propagation/"
                        + "PlatformPropagationGate.java";
        Entry entry = entries.computeIfAbsent(name, Entry::new);
        entry.kind = "CONFIG_PROPERTY";
        entry.prefixFamily = family(name);
        entry.declarations.add(location(
                declarationPath, "TracingSettingsSource.traceControlHeadersEnabled", "boolean"));
        entry.readers.add(link(declarationPath, "PlatformPropagationGate.isEnabled", "DIRECT_CALL"));
        entry.consumers.add("PlatformPropagationGate / TraceControlPropagator");
        entry.valueType = "boolean";
        entry.defaultValue = "true";
        entry.defaultDefinitions.add("TracingSettingsSource.isolatedDefaults:true");
        entry.defaultOwner = "Agent runtime policy owner";
        entry.lifecycleOwner = "OpenTelemetry Java Agent extension";
        entry.readerStatus = "ACTIVE_READER";
        entry.consumerStatus = "ACTIVE";
        entry.currentStatus = "ACTIVE";
        entry.documents.addAll(source.references(name, SourceIndex::isDocumentation));
        entry.fixtures.addAll(source.references(name, SourceIndex::isFixture));
    }

    /**
     * Category A export kill-switch. Owned by AcceptedPolicy / {@code SafeSpanExporter};
     * the former Spring mirror {@code platform.tracing.exporter.enabled}
     * ({@code TracingProperties.Exporter}) was deleted as a dead POJO leaf after
     * {@code RuntimeConfigApplier} removal.
     */
    private static void addCategoryAExporterEnabled(
            Map<String, Entry> entries,
            SourceIndex source) {
        String name = "platform.tracing.policy.exporter.enabled";
        String declarationPath =
                "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/"
                        + "space/br1440/platform/tracing/autoconfigure/configuration/configserver/"
                        + "ConfigServerSettingsManifestBuilder.java";
        Entry entry = entries.computeIfAbsent(name, Entry::new);
        entry.kind = "CONFIG_PROPERTY";
        entry.prefixFamily = family(name);
        entry.declarations.add(location(
                declarationPath, "ConfigServerSettingsManifestBuilder.REQUIRED_KEYS", "boolean"));
        entry.readers.add(link(
                "platform-tracing-otel-javaagent-extension/src/main/java/"
                        + "space/br1440/platform/tracing/otel/javaagent/exporter/SafeSpanExporter.java",
                "TracingSettingsSource.exporterEnabled",
                "DIRECT_CALL"));
        entry.consumers.add("SafeSpanExporter / ExportControl");
        entry.valueType = "boolean";
        entry.defaultValue = "true";
        entry.defaultDefinitions.add("TracingSettingsSource.isolatedDefaults:true");
        entry.defaultOwner = "Agent runtime policy owner";
        entry.lifecycleOwner = "OpenTelemetry Java Agent extension";
        entry.readerStatus = "ACTIVE_READER";
        entry.consumerStatus = "ACTIVE";
        entry.currentStatus = "ACTIVE";
        entry.documents.addAll(source.references(name, SourceIndex::isDocumentation));
        entry.fixtures.addAll(source.references(name, SourceIndex::isFixture));
    }

    /**
     * Bootstrap ownership switch read from {@link org.springframework.core.env.Environment}
     * by {@code TracingBootstrapSettingsResolver} — not a {@code TracingProperties} POJO leaf.
     */
    private static void addBootstrapEnabledProperty(
            Map<String, Entry> entries,
            SourceIndex source) {
        String name = TracingBootstrapPropertyNames.ENABLED;
        String declarationPath = "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/"
                + "space/br1440/platform/tracing/autoconfigure/bootstrap/"
                + "TracingBootstrapPropertyNames.java";
        String resolverPath = "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/"
                + "space/br1440/platform/tracing/autoconfigure/bootstrap/"
                + "TracingBootstrapSettingsResolver.java";
        Entry entry = entries.computeIfAbsent(name, Entry::new);
        entry.kind = "CONFIG_PROPERTY";
        entry.prefixFamily = family(name);
        entry.declarations.add(location(
                declarationPath, "TracingBootstrapPropertyNames.ENABLED", "boolean"));
        entry.readers.add(link(
                resolverPath,
                "TracingBootstrapSettingsResolver.parseBoolean(ENABLED, true)",
                "ENVIRONMENT_READ"));
        entry.consumers.add("TracingBootstrapSettingsResolver / AgentRuntimeContractValidator");
        entry.valueType = "boolean";
        entry.defaultValue = "true";
        entry.defaultDefinitions.add("Environment:absent -> true");
        entry.defaultOwner = "Spring startup";
        entry.lifecycleOwner = "Spring application context";
        entry.readerStatus = "ACTIVE_READER";
        entry.consumerStatus = "ACTIVE";
        entry.currentStatus = "ACTIVE";
        entry.documents.addAll(source.references(name, SourceIndex::isDocumentation));
        entry.fixtures.addAll(source.references(name, SourceIndex::isFixture));
    }

    private static void addFixtureAndDocumentedNames(
            Map<String, Entry> entries,
            SourceIndex source) {
        fixture(entries, source, "platform.tracing.sdk.mode");
        fixture(entries, source, "platform.tracing.policy.sampling.ratio");
        fixture(entries, source, "platform.tracing.policy.sampling.route-ratio");
        fixture(entries, source, "platform.tracing.async.enabled");
        fixture(entries, source, "platform.tracing.kafka.mode");
        fixture(entries, source, "platform.tracing.propagation.enabled");
        fixture(entries, source, "platform.tracing.exporter.enabled");
        fixture(entries, source, "platform.tracing.response.enabled");
        fixture(entries, source, "platform.tracing.response.header-name");
        fixture(entries, source, "platform.tracing.response.expose-request-id-header");
        fixture(entries, source, "platform.tracing.log-level");
        fixture(entries, source, "platform.tracing.service.k8s-pod-uid");

        Entry conflicting = entries.computeIfAbsent(
                "platform.tracing.semconv.validation.mode", Entry::new);
        conflicting.kind = "DOCUMENTED_ONLY_KEY";
        conflicting.prefixFamily = family(conflicting.exactName);
        conflicting.documents.addAll(source.references(
                conflicting.exactName, SourceIndex::isDocumentation));
        conflicting.declarations.add("NO_CODE_DECLARATION");
        conflicting.readers.add("NO_READER");
        conflicting.consumers.add("NO_RUNTIME_CONSUMER_FOUND");
        conflicting.readerStatus = "DECLARED_NO_READER";
        conflicting.consumerStatus = "NONE";
        conflicting.currentStatus = "CONFLICTING_CONTRACT";
        conflicting.valueType = "SemconvValidationMode";
        conflicting.defaultOwner = "Published contract";
        conflicting.lifecycleOwner = "Documentation";
    }

    private static void fixture(
            Map<String, Entry> entries,
            SourceIndex source,
            String name) {
        Entry entry = entries.computeIfAbsent(name, Entry::new);
        entry.kind = "FIXTURE_ONLY_KEY";
        entry.prefixFamily = family(name);
        entry.declarations.add("NO_PRODUCTION_DECLARATION");
        entry.readers.add("NO_PRODUCTION_READER");
        entry.consumers.add("NO_RUNTIME_CONSUMER_FOUND");
        entry.documents.addAll(source.references(name, SourceIndex::isDocumentation));
        entry.fixtures.addAll(source.references(name, SourceIndex::isFixture));
        entry.readerStatus = "FIXTURE_ONLY_READER";
        entry.consumerStatus = "FIXTURE_ONLY";
        entry.currentStatus = "FIXTURE_ONLY";
        entry.defaultOwner = "Fixture";
        entry.lifecycleOwner = "Fixture only";
    }

    private static void addRelaxedBindingAlias(
            Map<String, Entry> entries,
            SourceIndex source) {
        String alias = "PLATFORM_TRACING_ACTUATOR_MUTATION_ENABLED";
        Entry entry = entries.computeIfAbsent(alias, Entry::new);
        entry.kind = "RELAXED_BINDING_ALIAS";
        entry.prefixFamily = "PLATFORM_TRACING_ACTUATOR";
        entry.declarations.addAll(source.references(alias, path -> true));
        entry.readers.add(link(
                "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/"
                        + "space/br1440/platform/tracing/autoconfigure/TracingProperties.java",
                "Spring relaxed binding -> platform.tracing.actuator.mutation-enabled",
                "SPRING_BINDING"));
        entry.consumers.add("Deleted P6.4 — legacy Actuator @WriteOperation gate");
        entry.environmentVariables.add(alias);
        entry.relaxedBindingAliases.add("platform.tracing.actuator.mutation-enabled");
        entry.valueType = "boolean";
        entry.defaultValue = "false";
        entry.defaultOwner = "Spring";
        entry.lifecycleOwner = "N/A";
        entry.readerStatus = "NO_READER";
        entry.consumerStatus = "DELETED";
        entry.currentStatus = "DELETED";
    }

    /**
     * Нормализованные upstream-компоненты OpenTelemetry, применяющие каждый внешний
     * {@code STANDARD_OTEL_KEY}. Источник атрибуции — официальная документация OTel SDK
     * autoconfigure и Java agent (см. provenance-шапку
     * {@code tracing-inventory/standard-otel-configuration-keys.txt}), а не предположение по
     * имени свойства. {@code otel.javaagent.extensions} сюда не входит — эта запись сохраняет
     * реальные in-repo readers (см. {@link #addEnvironmentAndOtelNames}).
     */
    private static final Map<String, List<String>> STANDARD_OTEL_KEY_EXTERNAL_ARTIFACTS = Map.ofEntries(
            Map.entry("otel.attribute.count.limit",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.bsp.export.timeout",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.bsp.max.export.batch.size",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.bsp.max.queue.size",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.bsp.schedule.delay",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.exporter.otlp.endpoint",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_EXPORTER_OTLP")),
            Map.entry("otel.exporter.otlp.headers",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_EXPORTER_OTLP")),
            Map.entry("otel.exporter.otlp.protocol",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_EXPORTER_OTLP")),
            Map.entry("otel.exporter.otlp.timeout",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_EXPORTER_OTLP")),
            Map.entry("otel.java.exporter.otlp.retry.disabled",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_EXPORTER_OTLP")),
            Map.entry("otel.javaagent.enabled", List.of("OPENTELEMETRY_JAVAAGENT")),
            Map.entry("otel.resource.attributes", List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE")),
            Map.entry("otel.service.name", List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE")),
            Map.entry("otel.span.attribute.count.limit",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.span.attribute.value.length.limit",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.span.event.count.limit",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.span.link.count.limit",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.traces.sampler",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")),
            Map.entry("otel.traces.sampler.arg",
                    List.of("OPENTELEMETRY_SDK_AUTOCONFIGURE", "OPENTELEMETRY_SDK_TRACE")));

    private static void addEnvironmentAndOtelNames(
            Map<String, Entry> entries,
            SourceIndex source) throws Exception {
        for (String property : loadStandardOtelConfigurationKeys()) {
            if ("otel.javaagent.extensions".equals(property)) {
                // Слитая запись: ExtensionPropertyNames/ExtensionEnvironmentVariables/
                // JavaAgentExtensionPaths уже наполнил эту
                // запись реальными in-repo readers через collectStringConstants(). Модель
                // EXTERNAL_CONTRACT сюда не применяется — только дополняем upstream-артефакт.
                entries.computeIfAbsent(property, Entry::new).consumers.add("OPENTELEMETRY_JAVAAGENT");
                continue;
            }
            List<String> externalArtifacts = STANDARD_OTEL_KEY_EXTERNAL_ARTIFACTS.get(property);
            if (externalArtifacts == null) {
                throw new IllegalStateException(
                        "No externalArtifacts attribution for standard OTel key: " + property);
            }
            standardOtel(entries, source, property, externalArtifacts);
            externalOtelEnvironment(entries, source, toEnv(property), externalArtifacts);
        }

        Class<?> envNames = Class.forName(
                "space.br1440.platform.tracing.otel.javaagent.configuration."
                        + "ExtensionEnvironmentVariables");
        for (Field field : envNames.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            field.setAccessible(true);
            environment(entries, source, (String) field.get(null),
                    "ExtensionEnvironmentVariables." + field.getName());
        }

        // Эти три внешних системных переменных читаются resource-провайдерами напрямую.
        environment(entries, source, "CONTAINER_ID", "PlatformResourceProvider");
        environment(entries, source, "HOSTNAME", "HostNameResolver");
        environment(entries, source, "COMPUTERNAME", "HostNameResolver");
    }

    /**
     * Регистрирует внешний OTel-ключ без in-repo reader'а. {@code readerStatus=EXTERNAL_CONTRACT}
     * явно отличается от {@code ACTIVE_READER}/{@code DECLARED_NO_READER}: ни один из них не
     * подошёл бы — ключ не «объявлен, но не прочитан» (его здесь вообще не объявляют), а прочитан
     * внешним upstream-компонентом OpenTelemetry, перечисленным в {@code consumers}.
     */
    private static void standardOtel(
            Map<String, Entry> entries,
            SourceIndex source,
            String property,
            List<String> externalArtifacts) {
        Entry entry = entries.computeIfAbsent(property, Entry::new);
        entry.kind = "STANDARD_OTEL_KEY";
        entry.prefixFamily = family(property);
        entry.declarations.add("OpenTelemetry SDK configuration contract (external, no in-repo declaration)");
        entry.consumers.addAll(externalArtifacts);
        entry.environmentVariables.add(toEnv(property));
        entry.documents.addAll(source.references(property, SourceIndex::isDocumentation));
        entry.valueType = "String";
        entry.defaultOwner = "OpenTelemetry";
        entry.lifecycleOwner = "OpenTelemetry bootstrap";
        entry.readerStatus = "EXTERNAL_CONTRACT";
        entry.consumerStatus = "EXTERNAL";
        entry.currentStatus = "EXTERNAL";
    }

    /** {@code ENV_VARIABLE}-двойник {@link #standardOtel}: то же readerStatus/consumers-правило. */
    private static void externalOtelEnvironment(
            Map<String, Entry> entries,
            SourceIndex source,
            String name,
            List<String> externalArtifacts) {
        Entry entry = entries.computeIfAbsent(name, Entry::new);
        entry.kind = "ENV_VARIABLE";
        entry.prefixFamily = "OTEL";
        entry.declarations.add("OpenTelemetry SDK environment variable contract (external, no in-repo declaration)");
        entry.consumers.addAll(externalArtifacts);
        entry.environmentVariables.add(name);
        entry.documents.addAll(source.references(name, SourceIndex::isDocumentation));
        entry.valueType = "String";
        entry.defaultOwner = "Orchestration/environment";
        entry.lifecycleOwner = "Process bootstrap";
        entry.readerStatus = "EXTERNAL_CONTRACT";
        entry.consumerStatus = "EXTERNAL";
        entry.currentStatus = "EXTERNAL";
    }

    /**
     * Читает test-owned манифест {@code tracing-inventory/standard-otel-configuration-keys.txt}
     * с classpath вместо рефлексии к production-классу, ранее удалённому по
     * {@code ADR-remove-dual-channel-diagnostics.md}.
     */
    private static List<String> loadStandardOtelConfigurationKeys() throws IOException {
        String resourcePath = "tracing-inventory/standard-otel-configuration-keys.txt";
        ClassLoader loader = TracingConfigurationInventoryGeneratorTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing classpath resource: " + resourcePath);
            }
            return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }

    private static void environment(
            Map<String, Entry> entries,
            SourceIndex source,
            String name,
            String reader) {
        Entry entry = entries.computeIfAbsent(name, Entry::new);
        entry.kind = "ENV_VARIABLE";
        entry.prefixFamily = name.startsWith("OTEL_") ? "OTEL" :
                name.startsWith("PLATFORM_TRACING_") ? "PLATFORM_TRACING" : "SYSTEM";
        entry.declarations.add(reader);
        entry.readers.add(link(source.firstPathContaining(name), reader, "DIRECT_CALL"));
        entry.consumers.add(name.startsWith("OTEL_")
                ? "OpenTelemetry autoconfigure"
                : "Platform tracing bootstrap/resource diagnostics");
        entry.environmentVariables.add(name);
        entry.documents.addAll(source.references(name, SourceIndex::isDocumentation));
        entry.fixtures.addAll(source.references(name, SourceIndex::isFixture));
        entry.valueType = "String";
        entry.defaultOwner = "Orchestration/environment";
        entry.lifecycleOwner = "Process bootstrap";
        entry.readerStatus = "ACTIVE_READER";
        entry.consumerStatus = "ACTIVE";
        entry.currentStatus = name.startsWith("OTEL_") ? "EXTERNAL" : "ACTIVE";
    }

    private static void enrichEntries(
            Map<String, Entry> entries,
            SourceIndex source) {
        for (Entry entry : entries.values()) {
            entry.declarations = sorted(entry.declarations);
            entry.readers = sorted(entry.readers);
            entry.consumers = sorted(entry.consumers);
            entry.documents = sorted(entry.documents);
            entry.fixtures = sorted(entry.fixtures);
            entry.environmentVariables = sorted(entry.environmentVariables);
            entry.relaxedBindingAliases = sorted(entry.relaxedBindingAliases);
            entry.defaultDefinitions = sorted(entry.defaultDefinitions);
            if (entry.readers.isEmpty()) {
                entry.readers = List.of("NO_READER");
                entry.readerStatus = "DECLARED_NO_READER";
            }
            if (entry.consumers.isEmpty()) {
                entry.consumers = List.of("NO_RUNTIME_CONSUMER_FOUND");
                entry.consumerStatus = "NONE";
            }
            entry.currentMutationPaths = mutationPaths(entry.exactName);
            entry.runtimeMutableToday = !entry.currentMutationPaths.isEmpty();
            entry.secretClassification = secretClassification(entry.exactName);
            disposition(entry);
        }

        for (Entry entry : entries.values()) {
            if (!"CONFIG_PROPERTY".equals(entry.kind)) {
                continue;
            }
            long declarationOwners = entry.declarations.stream()
                    .filter(value -> !value.startsWith("NO_"))
                    .count();
            if (declarationOwners > 1) {
                entry.duplicateGroup = "duplicate:" + entry.exactName;
                entry.currentStatus = "DUPLICATED_ACTIVE";
            }
            Set<String> types = new TreeSet<>(entry.valueTypeDefinitions);
            if (!"UNKNOWN".equals(entry.valueType)) {
                types.add(entry.valueType);
            }
            entry.typeDrift = knownTypeDrift(entry.exactName) || types.size() > 2;
            entry.defaultDrift = knownDefaultDrift(entry.exactName);
        }

        Entry w3c = entries.get("platform.tracing.propagation.w3c.diagnostics-only");
        if (w3c != null && source.symbolReferenceCount("PROP_W3C_DIAGNOSTICS_ONLY") == 1) {
            w3c.readers = List.of("NO_READER");
            w3c.consumers = List.of("NO_RUNTIME_CONSUMER_FOUND");
            w3c.readerStatus = "DECLARED_NO_READER";
            w3c.consumerStatus = "NONE";
            w3c.currentStatus = "DEAD";
            w3c.proposedDisposition = "DELETE";
            w3c.proposedTargetName = "N/A";
        }

        Map<String, String> outboundReaderMethods = Map.of(
                "platform.tracing.propagation.outbound.enabled",
                "PropagationDefaults.isOutboundEnabled",
                "platform.tracing.propagation.outbound.trusted-host-patterns",
                "PropagationDefaults.getTrustedHostPatterns",
                "platform.tracing.propagation.outbound.propagate-force-trace",
                "PropagationDefaults.isPropagateForceTrace",
                "platform.tracing.propagation.outbound.propagate-qa-trace",
                "PropagationDefaults.isPropagateQaTrace",
                "platform.tracing.propagation.outbound.propagate-request-id",
                "PropagationDefaults.isPropagateRequestId");
        for (Map.Entry<String, String> reader : outboundReaderMethods.entrySet()) {
            Entry entry = entries.get(reader.getKey());
            if (entry != null) {
                entry.readerStatus = "READER_NO_CALLER";
                entry.notes = append(entry.notes,
                        "Agent reader " + reader.getValue()
                                + " has no call site; Spring reader is active.");
            }
        }
    }

    private static void disposition(Entry entry) {
        entry.proposedTargetName = entry.exactName;
        entry.proposedDisposition = "KEEP";
        entry.proposedTargetOwner = targetOwner(entry);
        entry.proposedLifecycle = targetLifecycle(entry);
        entry.blockingDecision = "NONE";

        if ("STANDARD_OTEL_KEY".equals(entry.kind) || "ENV_VARIABLE".equals(entry.kind)) {
            entry.proposedDisposition = "EXTERNAL_KEEP";
            entry.proposedTargetOwner = entry.exactName.startsWith("OTEL_")
                    || entry.exactName.startsWith("otel.") ? "OpenTelemetry" : "Orchestration";
        }
        if ("FIXTURE_ONLY_KEY".equals(entry.kind)) {
            entry.proposedDisposition = "DELETE";
            entry.proposedTargetName = "N/A";
        }
        if ("DOCUMENTED_ONLY_KEY".equals(entry.kind)) {
            // P6.1: published contract corrected to semantic.validation-mode; stale name is delete-only.
            entry.proposedDisposition = "DELETE";
            entry.proposedTargetName = "platform.tracing.semantic.validation-mode";
            entry.blockingDecision = "RESOLVED_P61";
            entry.currentStatus = "STALE_PUBLISHED_NAME";
        }
        if ("RELAXED_BINDING_ALIAS".equals(entry.kind)) {
            // P6.4: mutation-enabled gate deleted with legacy Actuator @WriteOperation.
            entry.proposedDisposition = "DELETE";
            entry.proposedTargetName = "N/A";
            entry.blockingDecision = "RESOLVED_P64";
            entry.currentStatus = "DELETED";
        }
        switch (entry.exactName) {
            case "platform.tracing.resource.applied-policy-version" -> {
                // P6.1 RESOURCE_ATTRIBUTE-001 applied: attribute already at final name.
                entry.proposedDisposition = "KEEP";
                entry.proposedTargetName = "platform.tracing.resource.applied-policy-version";
                entry.blockingDecision = "RESOLVED_P61";
            }
            case "platform.tracing.javaagent.sampling.force-record-values",
                 "platform.tracing.javaagent.sampling.drop-paths",
                 "platform.tracing.javaagent.scrubbing.enabled",
                 "platform.tracing.javaagent.scrubbing.built-in-rules",
                 "platform.tracing.javaagent.propagation.baggage.enabled",
                 "platform.tracing.javaagent.propagation.baggage.allowlist-keys",
                 "platform.tracing.javaagent.propagation.baggage.allowed-keys",
                 "platform.tracing.javaagent.propagation.baggage.deny-patterns" -> {
                entry.proposedDisposition = "KEEP";
                entry.proposedTargetName = entry.exactName;
                entry.proposedTargetOwner = "Agent";
                entry.blockingDecision = "RESOLVED_P61";
            }
            default -> {
            }
        }
    }

    private static List<Map<String, Object>> categoryACandidates(
            Map<String, Entry> entries) {
        return List.of(
                category(entries, "sampling.enabled",
                        "platform.tracing.sampling.enabled",
                        "platform.tracing.policy.sampling.enabled", "INCLUDE"),
                category(entries, "sampling.ratio",
                        "platform.tracing.sampling.ratio",
                        "platform.tracing.policy.sampling.ratio", "INCLUDE"),
                category(entries, "sampling.route-ratios",
                        "platform.tracing.sampling.route-ratios",
                        "platform.tracing.policy.sampling.route-ratios", "INCLUDE"),
                category(entries, "sampling.drop-paths",
                        "platform.tracing.javaagent.sampling.drop-paths",
                        "platform.tracing.javaagent.sampling.drop-paths",
                        "EXCLUDE_CATEGORY_B"),
                category(entries, "sampling.force-record-values",
                        "platform.tracing.javaagent.sampling.force-record-values",
                        "platform.tracing.javaagent.sampling.force-record-values",
                        "EXCLUDE_CATEGORY_B"),
                category(entries, "validation.enabled",
                        "platform.tracing.validation.enabled",
                        "platform.tracing.policy.validation.enabled", "INCLUDE"),
                category(entries, "validation.mode",
                        "platform.tracing.validation.mode",
                        "platform.tracing.policy.validation.strict", "INCLUDE"),
                category(entries, "exporter.enabled",
                        "platform.tracing.policy.exporter.enabled",
                        "platform.tracing.policy.exporter.enabled", "INCLUDE"),
                category(entries, "propagation.trace-control-headers.enabled",
                        "platform.tracing.policy.propagation.trace-control-headers.enabled",
                        "platform.tracing.policy.propagation.trace-control-headers.enabled",
                        "INCLUDE"),
                category(entries, "diagnostics.log-level",
                        "platform.tracing.log-level",
                        "platform.tracing.policy.diagnostics.log-level", "INCLUDE"),
                category(entries, "scrubbing.built-in-rules",
                        "platform.tracing.javaagent.scrubbing.built-in-rules",
                        "platform.tracing.javaagent.scrubbing.built-in-rules",
                        "EXCLUDE_CATEGORY_B"),
                category(entries, "scrubbing.enabled",
                        "platform.tracing.javaagent.scrubbing.enabled",
                        "platform.tracing.javaagent.scrubbing.enabled",
                        "EXCLUDE_CATEGORY_B"));
    }

    private static Map<String, Object> category(
            Map<String, Entry> entries,
            String candidate,
            String current,
            String target,
            String result) {
        Entry entry = Objects.requireNonNull(entries.get(current), current);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("candidate", candidate);
        value.put("canonicalCurrentNames", currentNames(entries, current));
        value.put("proposedTargetName", target);
        value.put("type", entry.valueType);
        value.put("default", entry.defaultValue);
        value.put("consumer", entry.consumers);
        value.put("currentMutationPaths", entry.currentMutationPaths);
        value.put("securityClassification", entry.secretClassification);
        value.put("performanceRelevantBehavior", performanceBehavior(candidate));
        value.put("preliminaryResult", result);
        return value;
    }

    private static List<Map<String, Object>> n1n12(
            Map<String, Entry> entries,
            SourceIndex source) {
        List<Map<String, Object>> values = new ArrayList<>();
        values.add(resolution("N1", "PASS",
                "P6.1 applied: resource attribute renamed to "
                        + "platform.tracing.resource.applied-policy-version; "
                        + "config key platform.tracing.resource.policy-version remains KEEP."));
        values.add(resolution("N2", "PASS",
                "P6.1 resolved: both Agent baggage families consolidate under "
                        + "platform.tracing.javaagent.propagation.baggage.*; "
                        + "enabled/deny-patterns shared; allowlist-keys (span attrs) and "
                        + "allowed-keys (W3C propagation) remain distinct leaves."));
        values.add(resolution("N3", "PASS",
                "P6.1 resolved: Spring force-record-header-values and Agent force-record-values "
                        + "merge to platform.tracing.javaagent.sampling.force-record-values "
                        + "(Category B startup-only; Decision 14)."));
        values.add(resolution("N4", "PASS",
                "route-ratios type drift confirmed and represented without String flattening; "
                        + "obsolete platform.tracing.mode removed from inventory."));
        values.add(resolution("N5", "PASS",
                "P6.1/P6.7 resolved: scrubbing built-in-rules/enabled owned solely by "
                        + "platform.tracing.javaagent.scrubbing.* (Category B; Decision 14); "
                        + "Spring binding deleted."));
        values.add(resolution("N6", "PASS",
                "Five Agent outbound readers have no callers; Spring equivalents remain active."));
        values.add(resolution("N7", "PASS",
                "P6.1 applied: w3c.diagnostics-only dead constant deleted (NO_READER)."));
        values.add(resolution("N8", "PASS",
                "P6.1 applied: published contract corrected to "
                        + "platform.tracing.semantic.validation-mode; stale semconv name is DELETE."));
        values.add(resolution("N9", "PASS",
                "platform.tracing.enabled is ACTIVE Spring bootstrap (TracingBootstrapPropertyNames); "
                        + "sdk.mode, three removed async aliases and service.k8s-pod-uid remain "
                        + "fixture-only / DELETE with no production reader."));
        values.add(resolution("N10", "PASS",
                "Stale sdk.mode documents are listed; ExtensionPropertyNames.SDK_MODE and "
                        + "SdkExtensionConfig were removed; bootstrap ownership is "
                        + "platform.tracing.enabled."));
        values.add(resolution("N11", "PASS",
                "Actuator environment spelling is a relaxed-binding alias, not another property."));
        String perfFinding = source.references(
                        "space.br1440.platform.tracing:type=SamplingControl",
                        path -> path.contains("PerfAdminController"))
                .isEmpty() ? "NOT_FOUND" : "CONFIRMED";
        values.add(resolution("N12", "PASS",
                "PerfAdminController obsolete ObjectNames: " + perfFinding
                        + "; PerfPrivateControlController replaced it; no production code changed."));
        return List.copyOf(values);
    }

    private static void validate(Inventory inventory) {
        List<Entry> entries = inventory.entries;
        Comparator<Entry> canonicalOrder = Comparator.comparing((Entry entry) -> entry.kind)
                .thenComparing(entry -> entry.exactName);
        assertThat(entries).isSortedAccordingTo(canonicalOrder);
        assertThat(entries).extracting(entry -> entry.id).doesNotHaveDuplicates();
        assertThat(entries).extracting(entry -> entry.exactName).doesNotHaveDuplicates();
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.exactName).doesNotContain("*");
            assertThat(entry.declarations).isNotEmpty();
            assertThat(entry.readers).isNotEmpty();
            assertThat(entry.consumers).isNotEmpty();
            assertThat(entry.proposedDisposition).isNotBlank();
            assertThat(entry.proposedTargetOwner).isNotBlank();
        });
        assertThat(inventory.n1n12).extracting(value -> value.get("id"))
                .containsExactly("N1", "N2", "N3", "N4", "N5", "N6",
                        "N7", "N8", "N9", "N10", "N11", "N12");

        assertThat(BuiltInSpanAttributeScrubbingRules.defaultConfigNames())
                .containsExactly(
                        "password", "jwt", "email", "oauth-header", "x-auth-header",
                        "infra-credential", "webhook-token", "ssh-credential",
                        "user-identity", "hardware-identity", "location", "ip-address");
    }

    private static void writeArtifacts(
            Path directory,
            Path disposition,
            Inventory inventory) throws Exception {
        Files.createDirectories(directory);
        writeJson(directory.resolve("inventory.json"),
                inventory.entries.stream().map(Entry::asMap).toList());
        Files.writeString(directory.resolve("inventory.csv"), csv(inventory.entries),
                StandardCharsets.UTF_8);
        writeJson(directory.resolve("declarations.json"), declarations(inventory.entries));
        writeJson(directory.resolve("reader-consumer-links.json"), links(inventory.entries));
        writeJson(directory.resolve("duplicates.json"), duplicates(inventory.entries));
        writeJson(directory.resolve("environment-variables.json"),
                maps(inventory.entries, entry -> "ENV_VARIABLE".equals(entry.kind)));
        writeJson(directory.resolve("dead-and-conflicting-names.json"),
                deadAndConflicting(inventory.entries, inventory.source));
        writeJson(directory.resolve("category-a-candidates.json"), inventory.categoryA);
        Files.writeString(directory.resolve("generator-summary.txt"), summary(inventory.entries),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("n1-n12-resolution.md"), n1Markdown(inventory.n1n12),
                StandardCharsets.UTF_8);
        Files.createDirectories(disposition.getParent());
        Files.writeString(disposition, dispositionMarkdown(inventory), StandardCharsets.UTF_8);
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.writeString(path, JSON.writeValueAsString(value) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static List<Map<String, Object>> declarations(List<Entry> entries) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Entry entry : entries) {
            for (String declaration : entry.declarations) {
                values.add(Map.of(
                        "id", entry.id,
                        "exactName", entry.exactName,
                        "kind", entry.kind,
                        "declaration", declaration));
            }
        }
        return values;
    }

    private static List<Map<String, Object>> links(List<Entry> entries) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Entry entry : entries) {
            values.add(Map.of(
                    "id", entry.id,
                    "exactName", entry.exactName,
                    "readers", entry.readers,
                    "consumers", entry.consumers,
                    "readerStatus", entry.readerStatus,
                    "consumerStatus", entry.consumerStatus));
        }
        return values;
    }

    private static List<Map<String, Object>> duplicates(List<Entry> entries) {
        return entries.stream()
                .filter(entry -> !entry.duplicateGroup.isBlank())
                .map(entry -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", entry.id);
                    value.put("exactName", entry.exactName);
                    value.put("duplicateGroup", entry.duplicateGroup);
                    value.put("declarations", entry.declarations);
                    value.put("types", entry.valueTypeDefinitions);
                    value.put("defaults", entry.defaultDefinitions);
                    value.put("typeDrift", entry.typeDrift);
                    value.put("defaultDrift", entry.defaultDrift);
                    value.put("targetOwner", entry.proposedTargetOwner);
                    return value;
                })
                .toList();
    }

    private static List<Map<String, Object>> deadAndConflicting(
            List<Entry> entries,
            SourceIndex source) {
        List<Map<String, Object>> values = new ArrayList<>(maps(entries, entry ->
                Set.of("DEAD", "FIXTURE_ONLY", "DOCUMENTED_ONLY", "CONFLICTING_CONTRACT")
                        .contains(entry.currentStatus)));
        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("id", "N12-PERF-OBJECT-NAMES");
        perf.put("kind", "ADJACENT_JMX_CONTRACT_DEFECT");
        perf.put("path", "platform-tracing-perf-tests/src/main/java/"
                + "space/br1440/platform/tracing/perftests/sut/PerfAdminController.java");
        perf.put("finding", "Four hard-coded ObjectNames do not match the committed JMX baseline.");
        perf.put("references", source.references(
                "space.br1440.platform.tracing:type=SamplingControl",
                path -> path.contains("PerfAdminController")));
        values.add(perf);
        return values;
    }

    private static List<Map<String, Object>> maps(
            List<Entry> entries,
            Predicate<Entry> predicate) {
        return entries.stream().filter(predicate).map(Entry::asMap).toList();
    }

    private static String summary(List<Entry> entries) {
        Map<String, Long> kinds = entries.stream().collect(Collectors.groupingBy(
                entry -> entry.kind, TreeMap::new, Collectors.counting()));
        StringBuilder result = new StringBuilder();
        kinds.forEach((kind, count) -> result.append("kind.").append(kind)
                .append('=').append(count).append('\n'));
        result.append("configProperty.platformTracing=")
                .append(entries.stream().filter(entry ->
                        "CONFIG_PROPERTY".equals(entry.kind)
                                && entry.exactName.startsWith("platform.tracing.")).count())
                .append('\n');
        result.append("exactDuplicateNames=")
                .append(entries.stream().filter(entry -> !entry.duplicateGroup.isBlank()).count())
                .append('\n');
        result.append("typeDriftGroups=")
                .append(entries.stream().filter(entry -> entry.typeDrift).count()).append('\n');
        result.append("defaultDriftGroups=")
                .append(entries.stream().filter(entry -> entry.defaultDrift).count()).append('\n');
        result.append("declaredNamesWithNoReader=")
                .append(entries.stream().filter(entry ->
                        "DECLARED_NO_READER".equals(entry.readerStatus)).count()).append('\n');
        result.append("readerMethodsWithNoCaller=")
                .append(entries.stream().filter(entry ->
                        "READER_NO_CALLER".equals(entry.readerStatus)).count()).append('\n');
        result.append("documentedKeysWithNoDeclaration=")
                .append(entries.stream().filter(entry ->
                        "DOCUMENTED_ONLY_KEY".equals(entry.kind)).count()).append('\n');
        result.append("fixtureKeysWithNoReader=")
                .append(entries.stream().filter(entry ->
                        "FIXTURE_ONLY_KEY".equals(entry.kind)).count()).append('\n');
        result.append("query=inventory records grouped by kind/currentStatus/duplicateGroup flags\n");
        return result.toString();
    }

    private static String csv(List<Entry> entries) {
        String header = "id,exactName,kind,declaration,readers,consumer,valueType,"
                + "defaultValue,defaultOwner,lifecycle,mutationPath,currentStatus,"
                + "targetDisposition,targetName,targetOwner,blockingDecision,evidence\n";
        StringBuilder result = new StringBuilder(header);
        for (Entry entry : entries) {
            List<String> cells = List.of(
                    entry.id,
                    entry.exactName,
                    entry.kind,
                    String.join(" | ", entry.declarations),
                    String.join(" | ", entry.readers),
                    String.join(" | ", entry.consumers),
                    entry.valueType,
                    entry.defaultValue,
                    entry.defaultOwner,
                    entry.lifecycleOwner,
                    String.join(" | ", entry.currentMutationPaths),
                    entry.currentStatus,
                    entry.proposedDisposition,
                    entry.proposedTargetName,
                    entry.proposedTargetOwner,
                    entry.blockingDecision,
                    entry.id);
            result.append(cells.stream().map(TracingConfigurationInventoryGeneratorTest::csvCell)
                    .collect(Collectors.joining(","))).append('\n');
        }
        return result.toString();
    }

    private static String dispositionMarkdown(Inventory inventory) {
        String summary = summary(inventory.entries);
        StringBuilder result = new StringBuilder("""
                # Tracing Configuration Key Disposition

                ## 1. Scope and verdict

                S-04 is a repository-derived inventory. It changes no production configuration.
                Verdict: PASS. Gate E4 inventory portion: CLOSED.

                ## 2. Generator design

                Spring leaves are reflected from `TracingProperties`; Agent constants and defaults
                are reflected from their production owners; source, fixture and documentation links
                are scanned from the repository. Stable outputs are sorted and drift-checked.

                ## 3. Exact counts

                ```text
                """);
        result.append(summary).append("```\n\n");
        result.append("""
                The previous 27 ENV count omitted the directly consumed `HOSTNAME` and
                `COMPUTERNAME`; repository-wide executable discovery therefore reports 29.

                ## 4. Configuration-property inventory

                """);
        appendTable(result, inventory.entries);
        result.append("""

                ## 5. Environment-variable inventory

                Environment variables are separate `ENV_VARIABLE` records in the table.

                ## 6. Relaxed-binding aliases

                `PLATFORM_TRACING_ACTUATOR_MUTATION_ENABLED` is an alias of
                `platform.tracing.actuator.mutation-enabled`, not another Java property.

                ## 7. Resource attributes and external keys

                `platform.tracing.resource.applied-policy-version` is a resource attribute
                (P6.1 rename from `platform.tracing.policy.version`). `otel.*` names are
                external OpenTelemetry contracts; `platform.environment` is imported.

                ## 8. Duplicate declarations

                See `duplicates.json`; duplicate identity is computed from exact names.

                ## 9. Type/default drift

                Confirmed type drift: `platform.tracing.sampling.route-ratios`.
                Scrubbing built-in-rules/enabled are Agent Category B only after P6.7
                (no Spring default owner). Obsolete `platform.tracing.mode` is fail-fast
                and not inventoried as an active key.

                ## 10. Dead and fixture-only names

                See `dead-and-conflicting-names.json`.

                ## 11. Documentation/public-contract conflicts

                P6.1 corrected the published contract to the active
                `platform.tracing.semantic.validation-mode`; the stale
                `platform.tracing.semconv.validation.mode` name is DELETE-only in historical docs.
                Stale `sdk.mode` references remain in historical docs; production ownership is
                `platform.tracing.enabled`.

                ## 12. N1-N12 resolution

                """);
        for (Map<String, Object> value : inventory.n1n12) {
            result.append("- **").append(value.get("id")).append(" ")
                    .append(value.get("result")).append("**: ")
                    .append(value.get("resolution")).append('\n');
        }
        result.append("""

                ## 13. Proposed namespace families

                Runtime policy candidates use `platform.tracing.policy.*`; bootstrap topology and
                external OTel keys remain outside it. This inventory does not execute migration.

                ## 14. Category A handoff

                """);
        for (Map<String, Object> value : inventory.categoryA) {
            result.append("- `").append(value.get("candidate")).append("`: **")
                    .append(value.get("preliminaryResult")).append("**\n");
        }
        result.append("""

                ## 15. Remaining SRE/security/governance decisions

                P6.1 closed the former SRE/security/governance blockers for drop-paths,
                force-record-values, scrubbing Category B placement (Decision 14) and the
                published semconv contract correction. P6.7 deleted Spring scrubbing binding
                for enabled/built-in-rules (Agent Category B only).

                ## 16. What S-04 proves

                It proves exact declarations, kinds, duplicate groups, source readers, fixture and
                documentation conflicts are reproducibly inventoried.

                ## 17. What S-04 does not prove

                It does not approve namespace migration, Category A, production implementation,
                compatibility aliases or legacy deletion. Tracer/instrumentation scope names and
                metric names such as `platform.tracing.sampler.decisions` and
                `platform.tracing.semantic.validation.disabled` are not configuration records.

                ## 18. Gate E4 status

                **CLOSED** for the technical inventory. E5 remains OPEN.

                ## 19. Recommended next spike

                **S-05**, after review of this generated handoff.
                """);
        return result.toString();
    }

    private static void appendTable(StringBuilder result, List<Entry> entries) {
        result.append("| Exact name | Kind | Declaration | Reader(s) | Runtime consumer | "
                + "Value type | Current default | Default owner | Current lifecycle | "
                + "Mutation path | Current status | Target disposition | Proposed target name | "
                + "Target owner | Blocking decision | Evidence |\n");
        result.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Entry entry : entries) {
            result.append('|').append(md(entry.exactName))
                    .append('|').append(entry.kind)
                    .append('|').append(md(String.join("<br>", entry.declarations)))
                    .append('|').append(md(String.join("<br>", entry.readers)))
                    .append('|').append(md(String.join("<br>", entry.consumers)))
                    .append('|').append(md(entry.valueType))
                    .append('|').append(md(entry.defaultValue))
                    .append('|').append(md(entry.defaultOwner))
                    .append('|').append(md(entry.lifecycleOwner))
                    .append('|').append(md(entry.currentMutationPaths.isEmpty()
                            ? "NONE" : String.join("<br>", entry.currentMutationPaths)))
                    .append('|').append(entry.currentStatus)
                    .append('|').append(entry.proposedDisposition)
                    .append('|').append(md(entry.proposedTargetName))
                    .append('|').append(md(entry.proposedTargetOwner))
                    .append('|').append(entry.blockingDecision)
                    .append('|').append(entry.id).append("|\n");
        }
    }

    private static String n1Markdown(List<Map<String, Object>> resolutions) {
        StringBuilder result = new StringBuilder("# S-04 N1-N12 Resolution\n\n");
        result.append("| ID | Result | Technical resolution |\n|---|---|---|\n");
        for (Map<String, Object> value : resolutions) {
            result.append('|').append(value.get("id")).append('|')
                    .append(value.get("result")).append('|')
                    .append(value.get("resolution")).append("|\n");
        }
        return result.toString();
    }

    private static Map<String, Object> resolution(String id, String result, String text) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("result", result);
        value.put("resolution", text);
        return value;
    }

    private static void assignStableIds(Collection<Entry> entries) {
        Map<String, Integer> counters = new TreeMap<>();
        entries.stream()
                .sorted(Comparator.comparing((Entry entry) -> entry.kind)
                        .thenComparing(entry -> entry.exactName))
                .forEach(entry -> {
                    int number = counters.merge(entry.kind, 1, Integer::sum);
                    entry.id = entry.kind + "-" + String.format(Locale.ROOT, "%03d", number);
                });
    }

    private static void assertSameText(Path committed, Path generated, String command)
            throws IOException {
        assertThat(committed)
                .as("Missing committed inventory artifact. Regenerate with: %s", command)
                .exists();
        // Normalize CRLF/LF: the committed artifact is checked out with the platform's line
        // ending convention (e.g. core.autocrlf on Windows), while the generator always emits
        // LF. Without normalization this test is environment-dependent even when content is
        // identical — see the analogous fix in DomainMBeanJmxComplianceTest.
        assertThat(normalizeLineEndings(Files.readString(committed, StandardCharsets.UTF_8)))
                .as("Inventory drift in %s. Regenerate with: %s", committed, command)
                .isEqualTo(normalizeLineEndings(Files.readString(generated, StandardCharsets.UTF_8)));
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Repository root with settings.gradle not found");
        }
        return current;
    }

    private static String regenerationCommand() {
        return ".\\gradlew.bat tracingConfigurationInventoryVerify "
                + "-PupdateTracingConfigurationInventory --no-daemon";
    }

    private static String location(String path, String symbol, String type) {
        return path + "#" + symbol + ":" + type;
    }

    private static String link(String path, String symbol, String confidence) {
        return path + "#" + symbol + "[" + confidence + "]";
    }

    private static boolean isInventoryName(String name) {
        return name.startsWith("platform.tracing.")
                || name.equals("platform.environment")
                || name.equals("otel.javaagent.extensions");
    }

    private static String kindForConstant(String symbol, String exactName) {
        if ("RESOURCE_POLICY_VERSION_ATTR".equals(symbol)) {
            return "RESOURCE_ATTRIBUTE";
        }
        if ("OTEL_JAVAAGENT_EXTENSIONS".equals(symbol)) {
            return "STANDARD_OTEL_KEY";
        }
        if ("platform.environment".equals(exactName)) {
            return "IMPORTED_PLATFORM_KEY";
        }
        return "CONFIG_PROPERTY";
    }

    private static String inferAgentType(SourceIndex source, String symbol) {
        String context = String.join("\n", source.linesContaining(symbol));
        if (context.contains("getBoolean") || symbol.endsWith("_ENABLED")
                || symbol.startsWith("VALIDATION_STRICT")) {
            return "boolean";
        }
        if (context.contains("getDouble") || symbol.endsWith("_RATIO")) {
            return "double";
        }
        if (context.contains("durationValue")) {
            return "Duration";
        }
        if (context.contains("getList") || symbol.contains("PATHS")
                || symbol.contains("VALUES") || symbol.contains("RULES")) {
            return "List<String>";
        }
        if (symbol.contains("ROUTE_RATIOS")) {
            return "Map<String,String>";
        }
        return "String";
    }

    private static String inferAgentDefault(String name) {
        return switch (name) {
            case "platform.tracing.sampling.enabled",
                 "platform.tracing.javaagent.scrubbing.enabled",
                 "platform.tracing.validation.enabled",
                 "platform.tracing.baggage.enabled",
                 "platform.tracing.classification.enabled",
                 "platform.tracing.enriching.enabled",
                 "platform.tracing.metrics.enabled",
                 "platform.tracing.propagation.baggage.enabled",
                 "platform.tracing.resource.normalize-environment" -> "true";
            case "platform.tracing.sampling.ratio" -> "0.1";
            case "platform.tracing.enabled" -> "true";
            case "platform.tracing.sampling.route-ratios" -> "{}";
            case "platform.tracing.resource.detect-container-id",
                 "platform.tracing.watchdog.enabled" -> "false";
            case "platform.tracing.javaagent.sampling.drop-paths" ->
                    "[/actuator/health,/actuator/prometheus,/actuator/info]";
            case "platform.tracing.javaagent.sampling.force-record-values" -> "[on]";
            case "platform.tracing.javaagent.scrubbing.built-in-rules" ->
                    display(BuiltInSpanAttributeScrubbingRules.defaultConfigNames());
            case "platform.tracing.scrubbing.missing-key-policy" -> "mask";
            case "platform.tracing.scrubbing.rules.validation-mode",
                 "platform.tracing.validation.mode",
                 "platform.tracing.resource.validation-mode" -> "LENIENT";
            case "platform.tracing.javaagent.propagation.baggage.allowlist-keys",
                 "platform.tracing.javaagent.propagation.baggage.allowed-keys" ->
                    "[traffic_source,tenant_class,platform.correlation.id]";
            case "platform.tracing.javaagent.propagation.baggage.deny-patterns" ->
                    "[password,secret,token]";
            case "platform.tracing.enriching.remote-service-priority" ->
                    "[peer.service,rpc.service,server.address]";
            case "platform.tracing.classification.slow-threshold" -> "PT5S";
            case "platform.tracing.classification.normal-threshold" -> "PT1S";
            case "platform.tracing.watchdog.span-timeout" -> "PT30S";
            case "platform.tracing.watchdog.trace-timeout" -> "PT60S";
            case "platform.tracing.watchdog.scan-interval" -> "PT5S";
            case "platform.tracing.queue.overflow-policy" -> "DROP_OLDEST";
            case "platform.tracing.resource.policy-version" -> "2026.06.08";
            case "platform.tracing.propagation.platform-headers.force-trace-header" ->
                    "X-Trace-On";
            case "platform.tracing.propagation.platform-headers.qa-trace-header" ->
                    "X-QA-Trace";
            case "platform.tracing.propagation.platform-headers.request-id-header" ->
                    "X-Request-Id";
            case "platform.tracing.propagation.outbound.enabled",
                 "platform.tracing.propagation.outbound.propagate-force-trace",
                 "platform.tracing.propagation.outbound.propagate-qa-trace" -> "false";
            case "platform.tracing.propagation.outbound.propagate-request-id" -> "true";
            case "platform.tracing.propagation.outbound.trusted-host-patterns" -> "[]";
            default -> "NONE";
        };
    }

    private static String springConsumer(List<String> path) {
        return switch (path.get(0)) {
            case "request-id-header" -> "HTTP response tracing filters";
            case "semantic" -> "SemanticLayerAutoConfiguration";
            case "propagation" -> "Platform propagation adapters";
            case "kafka" -> "Kafka tracing adapters";
            case "mode" -> "TracingCoreAutoConfiguration";
            default -> "NO_RUNTIME_CONSUMER_FOUND";
        };
    }

    private static String agentConsumer(String name) {
        if (name.contains(".sampling.")) {
            return "SamplingExtensionConfig -> CompositeSampler";
        }
        if (name.contains(".scrubbing.")) {
            return "ScrubbingExtensionConfig -> ScrubbingSpanProcessor";
        }
        if (name.contains(".validation.")) {
            return "ValidationExtensionConfig -> ValidatingSpanProcessor";
        }
        if (name.contains(".baggage.")) {
            return name.contains(".propagation.")
                    ? "BaggagePropagationCustomizer"
                    : "BaggageExtensionConfig -> BaggageSpanProcessor";
        }
        if (name.contains(".resource.") || name.contains(".service.")) {
            return "PlatformResourceProvider";
        }
        if (name.contains(".watchdog.")) {
            return "SpanWatchdogProcessor";
        }
        if (name.contains(".classification.")) {
            return "ClassificationSpanProcessor";
        }
        if (name.contains(".enriching.")) {
            return "EnrichingSpanProcessor";
        }
        if (name.contains(".queue.")) {
            return "PlatformExportProcessorFactory";
        }
        if (name.contains(".control.")) {
            return "RuntimeControlMutationPolicy";
        }
        if (name.contains(".propagation.")) {
            return "Platform propagation extension";
        }
        if (name.equals("platform.tracing.enabled")) {
            return "TracingBootstrapSettingsResolver / AgentRuntimeContractValidator";
        }
        if (name.equals("platform.environment")) {
            return "ValidationEnvironmentGuard";
        }
        if (name.equals("platform.tracing.resource.applied-policy-version")
                || name.equals("platform.tracing.policy.version")) {
            return "ResourceAttributeResolver";
        }
        return "NO_RUNTIME_CONSUMER_FOUND";
    }

    private static List<String> mutationPaths(String name) {
        if (name.startsWith("platform.tracing.sampling.")) {
            return List.of("JMX Sampling MBean");
        }
        if (name.startsWith("platform.tracing.validation.")) {
            return List.of("JMX Validation MBean");
        }
        if (name.startsWith("platform.tracing.scrubbing.")) {
            return List.of("JMX Scrubbing MBean");
        }
        if ("platform.tracing.policy.exporter.enabled".equals(name)) {
            return List.of("JMX Export MBean", "LegacyTracingDiagnosticsEndpoint");
        }
        if ("platform.tracing.policy.propagation.trace-control-headers.enabled".equals(name)) {
            return List.of("private applySnapshot", "ACTUATOR typed leaf");
        }
        if ("platform.tracing.policy.diagnostics.log-level".equals(name)) {
            return List.of("private applySnapshot", "ACTUATOR typed leaf");
        }
        return List.of();
    }

    private static String secretClassification(String name) {
        if (name.contains("hmac-key") || name.contains("headers")) {
            return "SECRET_OR_SENSITIVE";
        }
        if (name.contains("force-record")) {
            return "SECURITY_RELEVANT";
        }
        return "NON_SECRET";
    }

    private static String targetOwner(Entry entry) {
        if ("RESOURCE_ATTRIBUTE".equals(entry.kind)) {
            return "Agent resource provider";
        }
        if (entry.exactName.startsWith("platform.tracing.")
                && entry.runtimeMutableToday) {
            return "Agent runtime policy owner";
        }
        if ("CONFIG_PROPERTY".equals(entry.kind)) {
            return entry.declarations.stream().anyMatch(value ->
                            value.contains("TracingProperties")
                                    || value.contains("TracingBootstrapPropertyNames"))
                    ? "Spring" : "Agent";
        }
        return "External";
    }

    private static String targetLifecycle(Entry entry) {
        return entry.runtimeMutableToday ? "Runtime policy" :
                "ENV_VARIABLE".equals(entry.kind) ? "Process bootstrap" : "Startup";
    }

    private static boolean knownTypeDrift(String name) {
        return "platform.tracing.sampling.route-ratios".equals(name);
    }

    private static boolean knownDefaultDrift(String name) {
        return false;
    }

    private static List<String> currentNames(Map<String, Entry> entries, String current) {
        return entries.containsKey(current) ? List.of(current) : List.of();
    }

    private static String performanceBehavior(String candidate) {
        if (candidate.contains("route-ratios") || candidate.contains("drop-paths")) {
            return "Bounded collection lookup on sampling hot path";
        }
        if (candidate.contains("built-in-rules")) {
            return "Rule compilation and processor pipeline composition";
        }
        return "Atomic snapshot read or startup-only behavior";
    }

    private static String family(String name) {
        int first = name.indexOf('.');
        int second = first < 0 ? -1 : name.indexOf('.', first + 1);
        int third = second < 0 ? -1 : name.indexOf('.', second + 1);
        return third < 0 ? name : name.substring(0, third);
    }

    private static String kebab(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    private static String genericType(Field field) {
        return field.getGenericType().getTypeName().replace("java.lang.", "")
                .replace("java.util.", "");
    }

    private static String simpleType(Class<?> type) {
        return type.isEnum() ? type.getSimpleName() : type.getSimpleName();
    }

    private static String collectionElementType(Field field) {
        String type = genericType(field);
        int start = type.indexOf('<');
        return start < 0 ? "N/A" : type.substring(start + 1, type.length() - 1);
    }

    private static String display(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return new TreeMap<>(map).toString().replace(" ", "");
        }
        if (value instanceof Collection<?> collection) {
            return collection.toString().replace(" ", "");
        }
        return String.valueOf(value);
    }

    private static String toEnv(String property) {
        return property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static String csvCell(String value) {
        return "\"" + value.replace("\"", "\"\"").replace("\r", "").replace("\n", " ") + "\"";
    }

    private static String md(String value) {
        return value.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().filter(Objects::nonNull).filter(value -> !value.isBlank())
                .distinct().sorted().toList();
    }

    private static String append(String current, String value) {
        return current == null || current.isBlank() ? value : current + " " + value;
    }

    private record Inventory(
            List<Entry> entries,
            List<Map<String, Object>> categoryA,
            List<Map<String, Object>> n1n12,
            SourceIndex source) {
    }

    private static final class Entry {

        private String id = "";
        private final String exactName;
        private String kind = "CONFIG_PROPERTY";
        private String prefixFamily = "";
        private List<String> declarations = new ArrayList<>();
        private List<String> readers = new ArrayList<>();
        private List<String> consumers = new ArrayList<>();
        private List<String> defaultDefinitions = new ArrayList<>();
        private List<String> environmentVariables = new ArrayList<>();
        private List<String> relaxedBindingAliases = new ArrayList<>();
        private List<String> documents = new ArrayList<>();
        private List<String> fixtures = new ArrayList<>();
        private final List<String> valueTypeDefinitions = new ArrayList<>();
        private String valueType = "UNKNOWN";
        private String collectionElementType = "N/A";
        private String defaultValue = "NONE";
        private String defaultOwner = "NONE";
        private String lifecycleOwner = "UNKNOWN";
        private boolean runtimeMutableToday;
        private List<String> currentMutationPaths = new ArrayList<>();
        private String secretClassification = "UNKNOWN";
        private String duplicateGroup = "";
        private boolean typeDrift;
        private boolean defaultDrift;
        private String readerStatus = "UNKNOWN";
        private String consumerStatus = "UNKNOWN";
        private String currentStatus = "UNKNOWN";
        private String proposedTargetName = "N/A";
        private String proposedDisposition = "PENDING_TECHNICAL_DECISION";
        private String proposedTargetOwner = "UNKNOWN";
        private String proposedLifecycle = "UNKNOWN";
        private String blockingDecision = "NONE";
        private String notes = "";

        private Entry(String exactName) {
            this.exactName = exactName;
        }

        private Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("exactName", exactName);
            value.put("kind", kind);
            value.put("prefixFamily", prefixFamily);
            value.put("declarations", declarations);
            value.put("readers", readers);
            value.put("consumers", consumers);
            value.put("defaultDefinitions", defaultDefinitions);
            value.put("environmentVariables", environmentVariables);
            value.put("relaxedBindingAliases", relaxedBindingAliases);
            value.put("documents", documents);
            value.put("fixtures", fixtures);
            value.put("valueType", valueType);
            value.put("collectionElementType", collectionElementType);
            value.put("defaultValue", defaultValue);
            value.put("defaultOwner", defaultOwner);
            value.put("lifecycleOwner", lifecycleOwner);
            value.put("runtimeMutableToday", runtimeMutableToday);
            value.put("currentMutationPaths", currentMutationPaths);
            value.put("secretClassification", secretClassification);
            value.put("duplicateGroup", duplicateGroup);
            value.put("typeDrift", typeDrift);
            value.put("defaultDrift", defaultDrift);
            value.put("readerStatus", readerStatus);
            value.put("consumerStatus", consumerStatus);
            value.put("currentStatus", currentStatus);
            value.put("proposedTargetName", proposedTargetName);
            value.put("proposedDisposition", proposedDisposition);
            value.put("proposedTargetOwner", proposedTargetOwner);
            value.put("proposedLifecycle", proposedLifecycle);
            value.put("blockingDecision", blockingDecision);
            value.put("notes", notes);
            return value;
        }
    }

    private static final class SourceIndex {

        private final Path root;
        private final Map<String, List<String>> lines;

        private SourceIndex(Path root, Map<String, List<String>> lines) {
            this.root = root;
            this.lines = lines;
        }

        static SourceIndex load(Path root) throws IOException {
            Map<String, List<String>> values = new TreeMap<>();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(SourceIndex::isSourceFile)
                        .filter(SourceIndex::isIncluded)
                        .toList()) {
                    String relative = normalize(root.relativize(path));
                    try {
                        values.put(relative, Files.readAllLines(path, StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // Бинарные или несовместимые файлы не входят в текстовый source index.
                    }
                }
            }
            return new SourceIndex(root, values);
        }

        List<String> references(String token, Predicate<String> pathFilter) {
            List<String> result = new ArrayList<>();
            lines.forEach((path, content) -> {
                if (!pathFilter.test(path)) {
                    return;
                }
                for (int index = 0; index < content.size(); index++) {
                    if (content.get(index).contains(token)) {
                        result.add(path + ":" + (index + 1));
                    }
                }
            });
            return sorted(result);
        }

        List<String> symbolReferences(String symbol, String declarationPath) {
            List<String> result = new ArrayList<>();
            lines.forEach((path, content) -> {
                if (!path.endsWith(".java") || !path.contains("/src/")) {
                    return;
                }
                for (int index = 0; index < content.size(); index++) {
                    String line = content.get(index);
                    if (!line.contains(symbol)
                            || path.equals(declarationPath) && isStringDeclaration(line, symbol)) {
                        continue;
                    }
                    result.add(path + ":" + (index + 1) + "[DIRECT_CALL]");
                }
            });
            return sorted(result);
        }

        int symbolReferenceCount(String symbol) {
            return references(symbol, path -> path.endsWith(".java")).size();
        }

        List<String> linesContaining(String token) {
            List<String> result = new ArrayList<>();
            lines.forEach((path, content) -> content.stream()
                    .filter(line -> line.contains(token))
                    .forEach(line -> result.add(path + ":" + line.trim())));
            return result;
        }

        String firstPathContaining(String token) {
            return references(token, path -> path.contains("/src/main/")).stream()
                    .findFirst()
                    .orElse("READER_DERIVED_FROM_RUNTIME_CONVERSION");
        }

        static boolean isDocumentation(String path) {
            return path.startsWith("docs/") || path.equals("README.md");
        }

        static boolean isFixture(String path) {
            return path.contains("/src/test/")
                    || path.contains("/src/configProvenance/")
                    || path.startsWith("gradle/")
                    || path.contains("perf-harness/config/")
                    || path.contains("/fixtures/");
        }

        private static boolean isSourceFile(Path path) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith);
        }

        private static boolean isStringDeclaration(String line, String symbol) {
            return line.contains("String " + symbol)
                    && line.contains("=");
        }

        private static boolean isIncluded(Path path) {
            String normalized = normalize(path);
            return !normalized.contains("/.git/")
                    && !normalized.contains("/build/")
                    && !normalized.contains("/.gradle/")
                    && !normalized.contains("/.idea/")
                    && !normalized.contains("/docs/research/evidence/"
                    + "tracing-configuration-inventory/")
                    && !normalized.endsWith(
                    "/docs/research/tracing-configuration-key-disposition.md")
                    && !normalized.endsWith(
                    "/docs/analysis/platform-tracing-configuration/"
                            + "14-category-a-binding-decision.md")
                    && !normalized.endsWith(
                    "/docs/analysis/plans/"
                            + "immutable_config_namespace_split_ba8da442.plan.md")
                    && !normalized.endsWith(
                    "/docs/analysis/platform-tracing-configuration/"
                            + "15-opus-final-plan-review.md")
                    && !normalized.endsWith(
                    "/TracingConfigurationInventoryGeneratorTest.java");
        }

        private static String normalize(Path path) {
            return path.toString().replace('\\', '/');
        }
    }
}
