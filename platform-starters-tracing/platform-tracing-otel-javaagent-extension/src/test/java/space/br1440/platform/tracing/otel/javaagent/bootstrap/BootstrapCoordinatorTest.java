package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAuthorizationTestFixtures.mutationRegistrationPermit;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.DomainMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDeploymentDeclaration;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDeploymentTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposurePolicy;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

/**
 * Проверяет state machine {@link BootstrapCoordinator} и стабильные failure codes readiness MBean
 * без зависимости от реальной сборки {@code AutoConfiguredOpenTelemetrySdk}.
 */
@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class BootstrapCoordinatorTest {

    private BootstrapCoordinator ownedCoordinator;

    @AfterEach
    void closeOwnedCoordinator() {
        if (ownedCoordinator != null) {
            ownedCoordinator.close();
        }
    }

    @Test
    void activate_without_prior_prepare_classified_as_PLATFORM_ACP_NOT_EXECUTED() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create(
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedAttestor());

        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("PLATFORM_ACP_NOT_EXECUTED");
    }

    @Test
    void repeated_activate_after_successful_activation_classified_as_DUPLICATE_ACTIVATION() {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                hooks,
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedAttestor(),
                ignored -> RemoteJmxDeploymentTestFixtures
                        .startupVerifiedRemoteProhibitedPolicy()
        );
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);
        coordinator.activate();

        // when
        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("DUPLICATE_ACTIVATION");

        // then
        assertThat(coordinator.currentSnapshot().lifecycle()).isEqualTo("ACTIVE");
        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("READY");
        hooks.runHook();
        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("FAILED");
        assertThat(coordinator.currentSnapshot().wire().failureCode()).isEqualTo("JMX_TRANSPORT_STALE");
    }

    @Test
    void linkageErrorDuringRuntimeAttestationPublishesFailedBeforePropagation() {
        // given
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                new CapturingShutdownHookRegistry(),
                new PlatformScrubberFactory(),
                extensionVersion -> {
                    throw new NoClassDefFoundError("simulated runtime evidence linkage failure");
                },
                ignored -> RemoteJmxDeploymentTestFixtures
                        .startupVerifiedRemoteProhibitedPolicy()
        );
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));

        try {
            // when
            assertThatThrownBy(coordinator::activate)
                    .isInstanceOf(NoClassDefFoundError.class)
                    .hasMessage("simulated runtime evidence linkage failure");

            // then
            assertThat(coordinator.currentSnapshot().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentSnapshot().wire().failureCode())
                    .isEqualTo(ControlledAgentRuntimeAttestor.EXTENSION_LINKAGE_ERROR);
            assertThat(session.readiness().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
            assertThat(session.readiness().failureCode())
                    .isEqualTo(ControlledAgentRuntimeAttestor.EXTENSION_LINKAGE_ERROR);
        } finally {
            coordinator.closeDiagnosticHandle();
        }
    }

    @Test
    void activationRollbackFailurePublishesRollbackIncompleteAndPreservesOriginalCause() {
        // given
        IllegalStateException cleanupFailure = new IllegalStateException("simulated shutdown hook removal failure");
        ShutdownHookRegistry hooks = (threadName, action) -> () -> {
            throw cleanupFailure;
        };
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                hooks,
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedAttestor(),
                ignored -> RemoteJmxDeploymentTestFixtures
                        .startupVerifiedRemoteProhibitedPolicy()
        );
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));

        try {
            // when
            assertThatThrownBy(coordinator::activate)
                    .isInstanceOf(BootstrapException.class)
                    .satisfies(failure -> {
                        BootstrapException bootstrapFailure = (BootstrapException) failure;
                        assertThat(bootstrapFailure.failure().code())
                                .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
                        assertThat(bootstrapFailure.getCause().getSuppressed()).hasSize(1);
                        assertThat(bootstrapFailure.getCause().getSuppressed()[0].getCause())
                                .isSameAs(cleanupFailure);
                    });

            // then
            assertThat(coordinator.currentSnapshot().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentSnapshot().wire().failureCode())
                    .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
            assertThat(session.readiness().failureCode())
                    .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        } finally {
            coordinator.closeDiagnosticHandle();
        }
    }

    @Test
    void diagnosticCleanupFailureOverridesTransportStaleDuringShutdown() throws Exception {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                hooks,
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedAttestor(),
                ignored -> RemoteJmxDeploymentTestFixtures
                        .startupVerifiedRemoteProhibitedPolicy()
        );
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);
        coordinator.activate();
        IllegalStateException diagnosticFailure =
                new IllegalStateException("simulated diagnostic readiness cleanup failure");
        AutoCloseable originalDiagnosticHandle = replaceDiagnosticHandle(
                coordinator,
                () -> {
                    throw diagnosticFailure;
                }
        );

        try {
            // when
            hooks.runHook();

            // then
            assertThat(coordinator.currentSnapshot().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentSnapshot().wire().failureCode())
                    .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
            assertThat(coordinator.currentSnapshot().wire().evidenceGeneration()).isEqualTo(3L);
            assertThat(session.readiness().failureCode())
                    .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        } finally {
            originalDiagnosticHandle.close();
        }
    }

    @Test
    void second_prepare_with_identical_configuration_classified_as_SECOND_SDK_BUILD() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        coordinator.prepare(config(Map.of()));

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SECOND_SDK_BUILD");
    }

    @Test
    void second_prepare_with_different_configuration_classified_as_CONFIGURATION_SPLIT_BRAIN() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        coordinator.prepare(config(Map.of()));

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of(ExtensionPropertyNames.SAMPLING_RATIO, "0.42"))))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("CONFIGURATION_SPLIT_BRAIN");
    }

    @Test
    void failure_mid_activate_rolls_back_LIFO_and_keeps_diagnostic_MBean_with_FAILED() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        // Пре-отравление runtime-generation: сама activate() внутри вызовет registerRuntimeControls()
        // повторно и получит естественный IllegalStateException — без mock'ов и без изменения
        // production-кода ради тестируемости.
        DomainMBeanRegistrar.RuntimeRegistration preexistingRuntimeRegistration =
                session.jmxRegistrar().registerRuntimeControls(
                session.policy(),
                mutationRegistrationPermit());

        try {
            // when
            assertThatThrownBy(coordinator::activate)
                    .isInstanceOf(BootstrapException.class)
                    .extracting(failure -> ((BootstrapException) failure).failure().code())
                    .isEqualTo("ACTIVATION_FAILED");

            // then
            assertThat(session.readiness().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
            assertThat(session.readiness().failureCode()).isEqualTo("ACTIVATION_FAILED");
        } finally {
            preexistingRuntimeRegistration.close();
        }
    }

    @Test
    void remoteJmxGateDenialPublishesFailedDiagnosticSnapshotBeforeRuntimeRegistration() {
        // given
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                new CapturingShutdownHookRegistry(),
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedAttestor(),
                ignored -> RemoteJmxDeploymentTestFixtures.startupEvidenceUnknownPolicy()
        );
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                RemoteJmxDeploymentDeclaration.Mode.UNDECLARED.name())));

        try {
            // when
            assertThatThrownBy(coordinator::activate)
                    .isInstanceOf(BootstrapException.class)
                    .extracting(failure -> ((BootstrapException) failure).failure().code())
                    .isEqualTo(RemoteJmxExposurePolicy.FAILURE_EVIDENCE_UNKNOWN);

            // then
            assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentSnapshot().wire().batchId()).isEmpty();
            assertThat(coordinator.currentSnapshot().wire().failureCode())
                    .isEqualTo(RemoteJmxExposurePolicy.FAILURE_EVIDENCE_UNKNOWN);
            assertThat(session.readiness().failureCode())
                    .isEqualTo(RemoteJmxExposurePolicy.FAILURE_EVIDENCE_UNKNOWN);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void terminal_coordinator_repeats_same_code_for_any_subsequent_operation() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        assertThatThrownBy(coordinator::activate);

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("PLATFORM_ACP_NOT_EXECUTED");
    }

    /**
     * Не допускает повторного разделения {@code snapshot}/{@code wire} на независимо публикуемые
     * значения и наблюдения разорванной пары lifecycle-состояний.
     */
    @Test
    void currentSnapshot_never_visible_to_reader_in_inconsistent_combination_during_activate() throws InterruptedException {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);

        Set<String> observedPairs = new CopyOnWriteArraySet<>();
        AtomicBoolean pollerRunning = new AtomicBoolean(true);
        CountDownLatch pollerStarted = new CountDownLatch(1);

        Thread poller = new Thread(() -> {
            pollerStarted.countDown();
            while (pollerRunning.get()) {
                BootstrapSnapshot snapshot = coordinator.currentSnapshot();
                observedPairs.add(snapshot.lifecycle() + "/" + snapshot.wire().lifecycle());
            }
        }, "snapshot-poller");
        poller.start();
        pollerStarted.await(5, TimeUnit.SECONDS);

        try {
            // when
            coordinator.activate();

            // then
            Set<String> validPairs = Set.of("NEW/INITIALIZING", "ACTIVATING/INITIALIZING", "ACTIVE/READY");
            assertThat(observedPairs).isSubsetOf(validPairs);
            assertThat(coordinator.currentSnapshot().lifecycle()).isEqualTo("ACTIVE");
            assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("READY");
        } finally {
            pollerRunning.set(false);
            poller.join(5_000);
        }
    }

    @Test
    void shutdownPublishesTransportStaleBeforeClosingRegisteredResources() {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                hooks,
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedAttestor(),
                ignored -> RemoteJmxDeploymentTestFixtures
                        .startupVerifiedRemoteProhibitedPolicy()
        );
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);
        coordinator.activate();
        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("READY");

        // when
        hooks.runHook();

        // then
        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("FAILED");
        assertThat(coordinator.currentSnapshot().wire().failureCode()).isEqualTo("JMX_TRANSPORT_STALE");
        assertThat(coordinator.currentSnapshot().wire().evidenceGeneration()).isEqualTo(2L);
    }

    @Test
    void missing_processor_acknowledgement_fails_activation_and_never_publishes_READY() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        markAllCapabilitiesExcept(session, PlatformExtensionCapability.SANITIZER_INSTALLED);
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();
        session.scrubbingRuntime().acknowledgeExportPathProtected();

        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("ACTIVATION_FAILED");

        assertThat(session.readiness().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(session.readiness().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("FAILED");
        assertThat(coordinator.currentSnapshot().lifecycle()).isNotEqualTo("ACTIVE");
    }

    @Test
    void zero_wrapped_exporters_fails_activation_and_never_publishes_READY() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        markAllCapabilitiesExcept(session, PlatformExtensionCapability.SANITIZER_INSTALLED);
        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExportPathProtected();

        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("ACTIVATION_FAILED");

        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("FAILED");
        assertThat(session.readiness().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
    }

    @Test
    void unprotected_export_path_fails_activation_and_never_publishes_READY() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        markAllCapabilitiesExcept(session, PlatformExtensionCapability.SANITIZER_INSTALLED);
        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();

        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("ACTIVATION_FAILED");

        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("FAILED");
    }

    @Test
    void missing_non_sanitizer_capability_fails_activation_after_barrier() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (capability != PlatformExtensionCapability.PROPAGATION_HOOKS_INSTALLED
                    && capability != PlatformExtensionCapability.SANITIZER_INSTALLED) {
                session.readiness().markInstalled(capability);
            }
        }
        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();
        session.scrubbingRuntime().acknowledgeExportPathProtected();

        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("ACTIVATION_FAILED");

        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("FAILED");
        assertThat(session.readiness().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isTrue();
        assertThat(session.readiness().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
    }

    @Test
    void empty_built_in_rule_set_preserves_SANITIZER_RULE_SET_EMPTY() {
        // На test classpath AutoService регистрирует ExampleMerchantAccountRule;
        // пустой SPI-источник нужен, чтобы prepare() увидел пустой effective rule set.
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator(
                ScrubbingRuntimeTestFixtures.factory(List::of));

        assertThatThrownBy(() -> coordinator.prepare(emptyBuiltInRulesConfig()))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_RULE_SET_EMPTY");
    }

    @Test
    void fail_fast_missing_hmac_key_preserves_SANITIZER_HMAC_INITIALIZATION_FAILED() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of(
                ExtensionPropertyNames.SCRUBBING_MISSING_KEY_POLICY, "fail-fast"))))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_HMAC_INITIALIZATION_FAILED");
    }

    @Test
    void rule_source_failure_preserves_SANITIZER_RULE_LOADING_FAILED() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator(ScrubbingRuntimeTestFixtures.factory(() -> {
            throw new IllegalStateException("rule source unavailable");
        }));

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_RULE_LOADING_FAILED");
    }

    @Test
    void invalid_bundled_rule_metadata_preserves_SANITIZER_RULE_METADATA_INVALID() {
        SpanAttributeScrubbingRule brokenMetadata = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                throw new IllegalStateException("metadata accessor blew up");
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return ScrubbingDecision.keep();
            }
        };
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator(
                ScrubbingRuntimeTestFixtures.factory(() -> List.of(brokenMetadata)));

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_RULE_METADATA_INVALID");
    }

    @Test
    void linkage_error_from_rule_source_is_not_classified_as_a_sanitizer_failure() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator(ScrubbingRuntimeTestFixtures.factory(() -> {
            throw new NoClassDefFoundError("simulated missing SPI class");
        }));

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(NoClassDefFoundError.class)
                .hasMessage("simulated missing SPI class");
    }

    @Test
    void execute_preserves_SANITIZER_FOREIGN_SAFE_EXPORTER_and_fails_readiness() {
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));

        assertThatThrownBy(() -> coordinator.execute("EXPORTER_INITIALIZATION_FAILED", () -> {
            throw new SanitizerBootstrapException(
                    SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER,
                    "Foreign SafeSpanExporter is not protected by the session scrubbing runtime "
                            + "(space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter)");
        }))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);

        assertThat(session.readiness().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(session.readiness().failureCode())
                .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);
        assertThat(session.readiness().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("FAILED");
    }

    /**
     * Имитирует customizer chain реальной сборки {@code AutoConfiguredOpenTelemetrySdk}, чтобы
     * выполнить installation barrier без зависимости от SDK build.
     */
    private static void satisfyReadinessBarrier(AutoconfigureSession session) {
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            session.readiness().markInstalled(capability);
        }
        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();
        session.scrubbingRuntime().acknowledgeExportPathProtected();
    }

    private static void markAllCapabilitiesExcept(AutoconfigureSession session,
                                                  PlatformExtensionCapability excluded) {
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (capability != excluded) {
                session.readiness().markInstalled(capability);
            }
        }
    }

    private static ConfigProperties emptyBuiltInRulesConfig() {
        return config(Map.of(ExtensionPropertyNames.SCRUBBING_BUILT_IN_RULES, ""));
    }

    private static AutoCloseable replaceDiagnosticHandle(BootstrapCoordinator coordinator,
                                                         AutoCloseable replacement) {
        try {
            Field field = BootstrapCoordinator.class.getDeclaredField("diagnosticHandle");
            field.setAccessible(true);
            AutoCloseable original = (AutoCloseable) field.get(coordinator);
            field.set(coordinator, replacement);
            return original;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot replace diagnostic readiness handle for test", failure);
        }
    }

    private static ConfigProperties config(Map<String, String> data) {
        Map<String, String> effective = new HashMap<>(data);
        effective.putIfAbsent(
                RemoteJmxDeploymentDeclaration.MODE_PROPERTY,
                RemoteJmxDeploymentDeclaration.Mode.REMOTE_PROHIBITED.name());
        return new MapConfigProperties(effective);
    }

    private static final class MapConfigProperties implements ConfigProperties {
        private final Map<String, String> data;

        MapConfigProperties(Map<String, String> data) {
            this.data = new HashMap<>(data);
        }

        @Override
        public String getString(String name) {
            return data.get(name);
        }

        @Override
        public Boolean getBoolean(String name) {
            String value = data.get(name);
            return value == null ? null : Boolean.parseBoolean(value);
        }

        @Override
        public Integer getInt(String name) {
            String value = data.get(name);
            return value == null ? null : Integer.valueOf(value);
        }

        @Override
        public Long getLong(String name) {
            String value = data.get(name);
            return value == null ? null : Long.valueOf(value);
        }

        @Override
        public Double getDouble(String name) {
            String value = data.get(name);
            return value == null ? null : Double.valueOf(value);
        }

        @Override
        public Duration getDuration(String name) {
            return null;
        }

        @Override
        public java.util.List<String> getList(String name) {
            String value = data.get(name);
            if (value == null) {
                return null;
            }
            if (value.isBlank()) {
                return java.util.List.of();
            }
            return java.util.List.of(value.split("\\s*,\\s*"));
        }

        @Override
        public java.util.Map<String, String> getMap(String name) {
            return java.util.Map.of();
        }
    }

    private static final class CapturingShutdownHookRegistry implements ShutdownHookRegistry {

        private final AtomicReference<Runnable> action = new AtomicReference<>();

        @Override
        public Registration register(String threadName, Runnable registeredAction) {
            if (!action.compareAndSet(null, registeredAction)) {
                throw new IllegalStateException("Shutdown hook is already registered");
            }
            return () -> {
            };
        }

        void runHook() {
            Runnable registeredAction = action.get();
            if (registeredAction == null) {
                throw new IllegalStateException("Shutdown hook is not registered");
            }
            registeredAction.run();
        }
    }
}
