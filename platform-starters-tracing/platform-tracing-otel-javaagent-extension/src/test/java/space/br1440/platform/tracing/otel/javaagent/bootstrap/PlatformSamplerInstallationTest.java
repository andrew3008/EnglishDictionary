package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularDataSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.ExtensionRuntimeVerifier;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationException;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PlatformSamplerInstallationTest {

    private static final String MANAGED_SAMPLER_PROPERTY = "otel.traces.sampler";
    private static final String MANAGED_SAMPLER_ARGUMENT_PROPERTY = "otel.traces.sampler.arg";
    private static final String MANAGED_SDK_DISABLED_PROPERTY = "otel.sdk.disabled";
    private static final String SAMPLER_ARGUMENT_CANARY = "sampler-argument-canary-782c6a7f";
    private static final String LATE_DROP_PATH = "/late-drop";
    private static final String TEST_TRACE_ID = "00000000000000000000000000000001";

    @Test
    void real_sdk_installs_the_session_sampler_and_reaches_ready() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        try (BootstrapCoordinator coordinator = coordinator(server)) {
            AutoConfiguredOpenTelemetrySdk sdk = buildSdk(coordinator, lateCustomizer(ignored -> {
            }), Map.of());

            try {
                AutoconfigureSession session = coordinator.session();
                SdkTracerProvider tracerProvider = sdk.getOpenTelemetrySdk().getSdkTracerProvider();

                // when
                new ActivationAgentListener(coordinator).afterAgent(sdk);

                // then
                assertThat(tracerProvider.getSampler()).isSameAs(session.sampler());
                assertReadyWithPlatformSampler(coordinator.currentWire());
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }
    }

    @Test
    void late_sampler_customizer_cannot_replace_the_session_sampler() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AtomicBoolean samplerCustomizerInvoked = new AtomicBoolean();
        AutoConfigurationCustomizerProvider lateProvider = lateCustomizer(customizer ->
                customizer.addSamplerCustomizer((ignoredSampler, ignoredConfig) -> {
                    samplerCustomizerInvoked.set(true);
                    return Sampler.alwaysOn();
                })
        );

        try (BootstrapCoordinator coordinator = coordinator(server)) {
            AutoConfiguredOpenTelemetrySdk sdk = buildSdk(coordinator, lateProvider, Map.of());

            try {
                AutoconfigureSession session = coordinator.session();
                SdkTracerProvider tracerProvider = sdk.getOpenTelemetrySdk().getSdkTracerProvider();

                // when
                new ActivationAgentListener(coordinator).afterAgent(sdk);

                // then
                assertThat(samplerCustomizerInvoked).isTrue();
                assertThat(tracerProvider.getSampler()).isSameAs(session.sampler());
                assertReadyWithPlatformSampler(coordinator.currentWire());
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }
    }

    @Test
    void late_tracer_provider_customizer_is_detected_as_sampler_replacement() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AutoConfigurationCustomizerProvider lateProvider = lateCustomizer(customizer ->
                customizer.addTracerProviderCustomizer((builder, ignoredConfig) ->
                        builder.setSampler(Sampler.alwaysOn()))
        );

        try (BootstrapCoordinator coordinator = coordinator(server)) {
            AutoConfiguredOpenTelemetrySdk sdk = buildSdk(coordinator, lateProvider, Map.of());

            try {
                // when
                BootstrapException failure = catchThrowableOfType(
                        BootstrapException.class,
                        () -> new ActivationAgentListener(coordinator).afterAgent(sdk)
                );

                // then
                assertThat(failure.failure().effectiveCode()).isEqualTo("PLATFORM_SAMPLER_REPLACED");
                assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
                assertThat(coordinator.currentWire().failureCode()).isEqualTo("PLATFORM_SAMPLER_REPLACED");
                assertThat(coordinator.currentWire().capabilities())
                        .doesNotContain(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED.name());
                assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();
                assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isFalse();
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }
    }

    @Test
    void runtime_attestation_failure_prevents_tracer_provider_customization() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AtomicBoolean tracerProviderCustomizerInvoked = new AtomicBoolean();
        VerificationException attestationFailure = VerificationTestFixtures.failure(
                VerificationFailureCode.DISTRIBUTION_MISMATCH
        );
        ExtensionRuntimeVerifier failingVerifier = ignoredVersion -> {
            throw attestationFailure;
        };
        AutoConfigurationCustomizerProvider lateProvider = lateCustomizer(customizer ->
                customizer.addTracerProviderCustomizer((builder, ignoredConfig) -> {
                    tracerProviderCustomizerInvoked.set(true);
                    return builder;
                })
        );

        try (BootstrapCoordinator coordinator = coordinator(server, failingVerifier)) {
            // when
            BootstrapException failure = catchThrowableOfType(
                    BootstrapException.class,
                    () -> buildSdk(coordinator, lateProvider, Map.of())
            );

            // then
            assertThat(failure.failure().effectiveCode()).isEqualTo("DISTRIBUTION_MISMATCH");
            assertThat(failure.getCause()).isSameAs(attestationFailure);
            assertThat(tracerProviderCustomizerInvoked).isFalse();
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentWire().failureCode()).isEqualTo("DISTRIBUTION_MISMATCH");
        }
    }

    @ParameterizedTest(name = "late managed property {0}")
    @MethodSource("lateManagedProperties")
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    void late_managed_property_fails_before_tracer_provider_customization(ManagedProperty property) {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AtomicBoolean tracerProviderCustomizerInvoked = new AtomicBoolean();
        AutoConfigurationCustomizerProvider lateProvider = lateCustomizer(customizer -> {
            customizer.addPropertiesCustomizer(ignoredConfig -> Map.of(property.name(), property.value()));
            customizer.addTracerProviderCustomizer((builder, ignoredConfig) -> {
                tracerProviderCustomizerInvoked.set(true);
                return builder;
            });
        });

        try (AgentLogCapture logs = AgentLogCapture.installed();
             BootstrapCoordinator coordinator = coordinator(server)) {
            // when
            BootstrapException failure = catchThrowableOfType(
                    BootstrapException.class,
                    () -> buildSdk(coordinator, lateProvider, Map.of())
            );

            // then
            assertThat(failure.failure().effectiveCode()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentWire().failureCode()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(tracerProviderCustomizerInvoked).isFalse();
            assertThat(logs.events())
                    .filteredOn(event -> "BOOTSTRAP_PHASE_FAILED".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsEntry("failureCode", "CONFIGURATION_INITIALIZATION_FAILED")
                            .containsEntry("phase", "prepare")
                            .containsEntry("subphase", "configuration-validation")
                            .containsEntry("propertyName", property.name())
                            .doesNotContainValue(property.value()));
            if (property.canary()) {
                assertThat(logs.rendered()).doesNotContain(SAMPLER_ARGUMENT_CANARY);
            }
        }
    }

    @Test
    void late_drop_paths_are_shared_by_the_session_and_installed_sampler() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AutoConfigurationCustomizerProvider lateProvider = lateCustomizer(customizer ->
                customizer.addPropertiesCustomizer(ignoredConfig -> Map.of(
                        ExtensionPropertyNames.SAMPLING_DROP_PATHS,
                        LATE_DROP_PATH
                ))
        );

        try (BootstrapCoordinator coordinator = coordinator(server)) {
            AutoConfiguredOpenTelemetrySdk sdk = buildSdk(coordinator, lateProvider, Map.of());

            try {
                AutoconfigureSession session = coordinator.session();
                Sampler installedSampler = sdk.getOpenTelemetrySdk().getSdkTracerProvider().getSampler();

                // when
                new ActivationAgentListener(coordinator).afterAgent(sdk);
                SamplingDecision decision = installedSampler.shouldSample(
                        Context.root(),
                        TEST_TRACE_ID,
                        "GET /late-drop",
                        SpanKind.SERVER,
                        Attributes.of(AttributeKey.stringKey("url.path"), LATE_DROP_PATH),
                        List.of()
                ).getDecision();

                // then
                assertThat(installedSampler).isSameAs(session.sampler());
                assertThat(session.extensionConfig().sampling().dropPaths()).containsExactly(LATE_DROP_PATH);
                assertThat(decision).isEqualTo(SamplingDecision.DROP);
                assertReadyWithPlatformSampler(coordinator.currentWire());
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }
    }

    @Test
    void jmx_snapshots_update_the_sampler_captured_before_runtime_commands() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        try (BootstrapCoordinator coordinator = coordinator(server)) {
            AutoConfiguredOpenTelemetrySdk sdk = buildSdk(coordinator, lateCustomizer(ignored -> {
            }), Map.of());

            try {
                Sampler installedSampler = sdk.getOpenTelemetrySdk().getSdkTracerProvider().getSampler();
                new ActivationAgentListener(coordinator).afterAgent(sdk);
                String agentInstanceId = coordinator.currentWire().agentInstanceId();

                // when
                CompositeData ratioZero = applySnapshot(server, settingsRequest(agentInstanceId, 0L, 0.0d));
                SamplingDecision afterRatioZero = sampleWithoutRoute(installedSampler);
                CompositeData ratioOne = applySnapshot(server, settingsRequest(agentInstanceId, 1L, 1.0d));
                SamplingDecision afterRatioOne = sampleWithoutRoute(installedSampler);
                CompositeData stale = applySnapshot(server, settingsRequest(agentInstanceId, 1L, 0.0d));
                SamplingDecision afterStale = sampleWithoutRoute(installedSampler);

                // then
                assertThat(ratioZero.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                        .isEqualTo("APPLIED");
                assertThat(ratioZero.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE))
                        .isEqualTo(1L);
                assertThat(afterRatioZero).isEqualTo(SamplingDecision.DROP);
                assertThat(ratioOne.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                        .isEqualTo("APPLIED");
                assertThat(ratioOne.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE))
                        .isEqualTo(2L);
                assertThat(afterRatioOne).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
                assertThat(stale.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                        .isEqualTo("STALE");
                assertThat(stale.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE))
                        .isEqualTo(2L);
                assertThat(afterStale).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }
    }

    private static Stream<ManagedProperty> lateManagedProperties() {
        return Stream.of(
                new ManagedProperty(MANAGED_SAMPLER_PROPERTY, "always_on", false),
                new ManagedProperty(MANAGED_SAMPLER_ARGUMENT_PROPERTY, SAMPLER_ARGUMENT_CANARY, true),
                new ManagedProperty(MANAGED_SDK_DISABLED_PROPERTY, "true", false),
                new ManagedProperty(ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS, "extension.jar", false)
        );
    }

    private static BootstrapCoordinator coordinator(MBeanServer server) {
        return coordinator(server, RuntimeEvidenceTestFixtures.trustedVerifier());
    }

    private static BootstrapCoordinator coordinator(MBeanServer server,
                                                    ExtensionRuntimeVerifier runtimeVerifier) {
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        return new BootstrapCoordinator(
                (threadName, action) -> {
                },
                new PlatformScrubberFactory(),
                runtimeVerifier,
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor,
                new PlatformTracingMBeanRegistrar(server, agentInstanceId),
                new DiagnosticReadinessHandle()
        );
    }

    private static AutoConfiguredOpenTelemetrySdk buildSdk(BootstrapCoordinator coordinator,
                                                           AutoConfigurationCustomizerProvider lateProvider,
                                                           Map<String, String> additionalProperties) {
        AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder()
                .setComponentLoader(componentLoader(coordinator, lateProvider))
                .disableShutdownHook()
                .addPropertiesSupplier(() -> properties(additionalProperties));
        return builder.build();
    }

    private static Map<String, String> properties(Map<String, String> additionalProperties) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("otel.metrics.exporter", "none");
        properties.put("otel.logs.exporter", "none");
        properties.put("otel.traces.exporter", "logging");
        properties.put("otel.propagators", "tracecontext,baggage,platform-trace-control");
        properties.put("platform.tracing.sampling.ratio", "1.0");
        properties.putAll(additionalProperties);
        return Map.copyOf(properties);
    }

    private static ComponentLoader componentLoader(BootstrapCoordinator coordinator,
                                                   AutoConfigurationCustomizerProvider lateProvider) {
        ComponentLoader delegate = ComponentLoader.forClassLoader(
                PlatformSamplerInstallationTest.class.getClassLoader()
        );

        return new ComponentLoader() {
            @Override
            public <T> Iterable<T> load(Class<T> spiClass) {
                if (!AutoConfigurationCustomizerProvider.class.equals(spiClass)) {
                    return delegate.load(spiClass);
                }

                List<T> providers = new ArrayList<>();
                boolean platformProviderFound = false;
                for (T provider : delegate.load(spiClass)) {
                    if (provider.getClass().equals(BootstrapAutoConfigurationCustomizerProvider.class)) {
                        platformProviderFound = true;
                    } else {
                        providers.add(provider);
                    }
                }

                if (!platformProviderFound) {
                    throw new IllegalStateException("Service-loaded platform ACP was not found");
                }

                providers.add(spiClass.cast(new BootstrapAutoConfigurationCustomizerProvider(coordinator)));
                providers.add(spiClass.cast(lateProvider));
                return List.copyOf(providers);
            }
        };
    }

    private static AutoConfigurationCustomizerProvider lateCustomizer(Consumer<AutoConfigurationCustomizer> registration) {
        return new LateCustomizerProvider(registration);
    }

    private static void assertReadyWithPlatformSampler(ExtensionReadinessWireSnapshot snapshot) {
        assertThat(snapshot.lifecycle()).isEqualTo("READY");
        assertThat(snapshot.capabilities())
                .contains(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED.name());
    }

    private static SamplingDecision sampleWithoutRoute(Sampler sampler) {
        return sampler.shouldSample(
                Context.root(),
                TEST_TRACE_ID,
                "runtime-policy-test",
                SpanKind.INTERNAL,
                Attributes.empty(),
                List.of()
        ).getDecision();
    }

    private static CompositeData settingsRequest(String agentInstanceId,
                                                 long expectedAcceptedSequence,
                                                 double samplingRatio) throws OpenDataException {
        TabularDataSupport routeRatios = new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS);
        Map<String, Object> route = new LinkedHashMap<>();
        route.put(JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX, "/orders");
        route.put(JmxWireContract.FIELD_ROUTE_RATIO_RATIO, 0.5d);
        routeRatios.put(new CompositeDataSupport(JmxOpenTypes.ROUTE_RATIO, route));

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put(JmxWireContract.FIELD_RUNTIME_POLICY_DIAGNOSTICS_LOG_LEVEL, "INFO");
        policy.put(JmxWireContract.FIELD_RUNTIME_POLICY_EXPORTER_ENABLED, true);
        policy.put(JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ENABLED, true);
        policy.put(JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_RATIO, samplingRatio);
        policy.put(JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ROUTE_RATIOS, routeRatios);
        policy.put(JmxWireContract.FIELD_RUNTIME_POLICY_TRACE_CONTROL_HEADERS_ENABLED, true);
        policy.put(JmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_LEVEL, "WARNING");
        CompositeData policyData = new CompositeDataSupport(JmxOpenTypes.RUNTIME_POLICY, policy);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID, UUID.randomUUID().toString());
        request.put(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_ACCEPTED_SEQUENCE,
                expectedAcceptedSequence
        );
        request.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_AGENT_INSTANCE_ID, agentInstanceId);
        request.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_RUNTIME_POLICY, policyData);
        request.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_WRITER, "CONFIG_SERVER");
        return new CompositeDataSupport(JmxOpenTypes.APPLY_SNAPSHOT_REQUEST, request);
    }

    private static CompositeData applySnapshot(MBeanServer server, CompositeData request) throws Exception {
        return (CompositeData) server.invoke(
                DomainObjectNames.SETTINGS_MUTATION,
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                new Object[]{request},
                new String[]{CompositeData.class.getName()}
        );
    }

    private record ManagedProperty(String name, String value, boolean canary) {
    }

    private record LateCustomizerProvider(Consumer<AutoConfigurationCustomizer> registration)
            implements AutoConfigurationCustomizerProvider {

        private static final int LATE_ORDER = 101;

        @Override
        public void customize(AutoConfigurationCustomizer customizer) {
            registration.accept(customizer);
        }

        @Override
        public int order() {
            return LATE_ORDER;
        }
    }
}
