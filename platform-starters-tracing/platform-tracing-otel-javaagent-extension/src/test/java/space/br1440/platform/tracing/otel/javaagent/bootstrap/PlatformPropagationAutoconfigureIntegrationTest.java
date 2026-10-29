package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.PropagatorConfigurationContract;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;

@DisplayName("Пропагация: platform propagator через реальный autoconfigure")
@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class PlatformPropagationAutoconfigureIntegrationTest {

    private static final List<String> TESTED_ADDITIONAL_PROPAGATORS =
            List.of("b3", "b3multi", "platform-trace-control");

    private static AutoConfiguredOpenTelemetrySdkBuilder baseBuilder() {
        return baseBuilder(RuntimeEvidenceTestFixtures.coordinator());
    }

    private static AutoConfiguredOpenTelemetrySdkBuilder baseBuilder(BootstrapCoordinator coordinator) {
        AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(AutoConfigurationCustomizerTestClassLoader.withoutDiscoveredCustomizers())
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.traces.exporter", "none")
                );
        new BootstrapAutoConfigurationCustomizerProvider(coordinator).customize(builder);
        return builder;
    }

    @Test
    @DisplayName("default: otel.propagators не задан → platform-trace-control добавлен")
    void default_propagators_include_platform() {
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder().build();
        try {
            // when
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // then
            assertThat(root.fields())
                    .as("platform-trace-control дописан в otel.propagators по умолчанию")
                    .contains("X-Trace-On", "X-QA-Trace", "X-Request-Id");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("ENV-aware: otel.propagators задан через property-source → platform всё равно добавлен")
    void env_propagators_still_get_platform() {
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of("otel.propagators", "tracecontext,baggage"))
                .build();
        try {
            // when
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // then
            assertThat(root.fields())
                    .as("addPropertiesCustomizer (ENV-aware) дописывает platform даже при заданном otel.propagators")
                    .contains("X-Trace-On", "X-QA-Trace", "X-Request-Id")
                    .contains("traceparent");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("явный otel.propagators=...,platform-trace-control → без дубля (fields стабильны)")
    void explicit_propagators_no_duplicate() {
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "tracecontext,baggage,platform-trace-control"))
                .build();
        try {
            // when
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // then
            assertThat(root.fields()).contains("X-Trace-On", "X-QA-Trace", "X-Request-Id");
            assertThat(root.fields()).contains("traceparent");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("characterization: tracecontext,baggage,platform-trace-control → final fields без дублей")
    void finalFields_containAllExpectedHeaders_withoutDuplicates_characterization() {
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "tracecontext,baggage,platform-trace-control"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // when
            java.util.Collection<String> fields = root.fields();

            // then
            assertThat(fields).containsExactlyInAnyOrder(
                    "traceparent", "tracestate", "baggage",
                    "X-Trace-On", "X-QA-Trace", "X-Request-Id"
            );
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("integration: outbound baggage через final SDK propagator фильтрует allowlist, "
            + "кодирует %20 и проходит stock round-trip")
    void outboundBaggage_throughRealAutoconfigure_filtersAndRoundTrips() {
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "tracecontext,baggage",
                        "platform.tracing.baggage.outbound-header.allowed-keys", "tenant_class"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // given
            io.opentelemetry.context.Context ctx = io.opentelemetry.context.Context.root().with(
                    io.opentelemetry.api.baggage.Baggage.builder()
                            .put("tenant_class", "DF 28",
                                    io.opentelemetry.api.baggage.BaggageEntryMetadata.create("verified=true"))
                            .put("traffic_source", "organic")
                            .put("internal_secret", "leak")
                            .build());
            Map<String, String> carrier = new java.util.HashMap<>();

            // when
            root.inject(ctx, carrier, (c, k, v) -> Objects.requireNonNull(c, "carrier").put(k, v));

            // then
            String header = carrier.get("baggage");
            assertThat(header)
                    .as("итоговый final SDK propagator обязан кодировать пробел как %%20")
                    .contains("tenant_class=DF%2028")
                    .doesNotContain("traffic_source")
                    .doesNotContain("internal_secret")
                    .doesNotContain("leak");

            // and: тот же final propagator должен вернуть исходное значение через extract
            io.opentelemetry.context.Context extracted =
                    root.extract(io.opentelemetry.context.Context.root(), carrier,
                            new io.opentelemetry.context.propagation.TextMapGetter<>() {
                                @Override
                                public Iterable<String> keys(Map<String, String> c) {
                                    return c.keySet();
                                }

                                @Override
                                public String get(Map<String, String> c, String key) {
                                    return c == null ? null : c.get(key);
                                }
                            });
            assertThat(io.opentelemetry.api.baggage.Baggage.fromContext(extracted).getEntryValue("tenant_class"))
                    .isEqualTo("DF 28");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @ParameterizedTest(name = "otel.propagators={0}, baggage enabled={1}")
    @MethodSource("supportedPropagationConfigurations")
    @DisplayName("Каждый разрешённый пропагатор соблюдает флаг и политику исходящего baggage")
    void supportedPropagators_obeyBaggagePolicy(String propagators, boolean enabled) {
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        OtelSdkPropertyNames.PROPAGATORS, propagators,
                        "platform.tracing.baggage.propagation.enabled", Boolean.toString(enabled),
                        "platform.tracing.baggage.outbound-header.allowed-keys", "traffic_source,access_token"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();
            SpanContext spanContext = SpanContext.create(
                    "0123456789abcdef0123456789abcdef",
                    "0123456789abcdef",
                    TraceFlags.getSampled(),
                    TraceState.getDefault());
            Context ctx = Context.root()
                    .with(Span.wrap(spanContext))
                    .with(Baggage.builder()
                            .put("traffic_source", "qa")
                            .put("access_token", "secret-token")
                            .put("platform.correlation.id", "reserved-id")
                            .put("password_x", "secret-password")
                            .put("unlisted_key", "unlisted-value")
                            .build());
            Map<String, String> carrier = new HashMap<>();

            // when
            root.inject(ctx, carrier, (c, k, v) -> Objects.requireNonNull(c, "carrier").put(k, v));

            // then
            assertThat(carrier.get("traceparent"))
                    .isEqualTo("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");

            if (propagators.endsWith(",b3")) {
                assertThat(carrier).containsEntry("b3", "0123456789abcdef0123456789abcdef-0123456789abcdef-1");
            }

            if (propagators.endsWith(",b3multi")) {
                assertThat(carrier).containsEntry("X-B3-TraceId", "0123456789abcdef0123456789abcdef");
            }

            if (enabled) {
                assertThat(carrier).containsEntry("baggage", "traffic_source=qa");
            } else {
                assertThat(root.fields()).doesNotContain("baggage");
                assertThat(carrier).doesNotContainKey("baggage");
            }
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    private static Stream<Arguments> supportedPropagationConfigurations() {
        return Stream.concat(Stream.of("tracecontext,baggage"),
                        TESTED_ADDITIONAL_PROPAGATORS.stream().map(name -> "tracecontext,baggage," + name))
                .flatMap(names -> Stream.of(Arguments.of(names, false), Arguments.of(names, true)));
    }

    @Test
    void sdkInvariant_coversEverySupportedPropagator() {
        // given
        Stream<String> additionalPropagators = PropagatorConfigurationContract.SUPPORTED_PROPAGATORS.stream()
                .filter(name -> !List.of(PropagatorConfigurationContract.NONE, "tracecontext", "baggage").contains(name));

        // when
        List<String> names = additionalPropagators.toList();

        // then
        assertThat(TESTED_ADDITIONAL_PROPAGATORS).containsExactlyInAnyOrderElementsOf(names);
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidPropagationConfigurations")
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    void invalidPropagationConfiguration_failsDuringPreparation_withPropertyName(
            String propertyName, String value, String diagnostic) {
        // given
        try (AgentLogCapture logs = AgentLogCapture.installed();
             BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator()) {
            AutoConfiguredOpenTelemetrySdkBuilder builder = baseBuilder(coordinator)
                    .addPropertiesSupplier(() -> Map.of(propertyName, value));

            // when
            BootstrapException failure = catchThrowableOfType(BootstrapException.class, builder::build);

            // then
            assertThat(failure).isNotNull().hasStackTraceContaining(diagnostic);
            assertThat(failure.failure().effectiveCode()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentWire().failureCode()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(logs.events())
                    .filteredOn(event -> "BOOTSTRAP_PHASE_FAILED".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsEntry("failureCode", "CONFIGURATION_INITIALIZATION_FAILED")
                            .containsEntry("phase", "prepare")
                            .containsEntry("subphase", "configuration-validation")
                            .containsEntry("propertyName", propertyName));
        }
    }

    private static Stream<Arguments> invalidPropagationConfigurations() {
        return Stream.of(
                Arguments.of(OtelSdkPropertyNames.PROPAGATORS, "jaeger", "unsupported propagators [jaeger]"),
                Arguments.of(OtelSdkPropertyNames.PROPAGATORS, "ottrace", "unsupported propagators [ottrace]"),
                Arguments.of(OtelSdkPropertyNames.PROPAGATORS, "tracecontext,jaeger", "unsupported propagators [jaeger]"),
                Arguments.of(OtelSdkPropertyNames.PROPAGATORS, "JAEGER", "unsupported propagators [JAEGER]"),
                Arguments.of("platform.tracing.baggage.outbound-header.deny-patterns", "[", "invalid regular expression"),
                Arguments.of("platform.tracing.baggage.span-attributes.deny-patterns", "[", "invalid regular expression"),
                Arguments.of("platform.tracing.baggage.outbound-header.allowed-keys", "platform.correlation.id",
                        "must not contain reserved key platform.correlation.id"),
                Arguments.of("platform.tracing.baggage.outbound-header.allowed-keys", "Platform.Correlation.Id",
                        "must not contain reserved key platform.correlation.id")
        );
    }

    @Test
    @DisplayName("otel.propagators=none → платформенный пропагатор НЕ добавляется")
    void propagators_none_excludes_platform() {
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of("otel.propagators", "none"))
                .build();
        try {
            // when
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // then
            assertThat(root.fields())
                    .as("none отключает всю пропагацию — платформенные заголовки не участвуют")
                    .doesNotContain("X-Trace-On", "X-QA-Trace", "X-Request-Id");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }
}
