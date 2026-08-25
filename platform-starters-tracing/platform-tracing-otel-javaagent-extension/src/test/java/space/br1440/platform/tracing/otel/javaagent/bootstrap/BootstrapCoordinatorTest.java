package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle.AutoConfiguredSdkPrivateControlIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

/**
 * Direct state-machine tests for {@link BootstrapCoordinator} (r5 plan &sect;3, &sect;4,
 * S4A, S5 Gate C1). These exercise the exact classified failure codes that
 * {@code AgentStartupVerifier} (Spring side, Gate C1) must be able to observe opaquely through
 * the readiness MBean, without depending on a real {@code AutoConfiguredOpenTelemetrySdk} build.
 */
@ExtendWith(AutoConfiguredSdkPrivateControlIsolationExtension.class)
class BootstrapCoordinatorTest {

    @Test
    void activate_without_prior_prepare_classified_as_PLATFORM_ACP_NOT_EXECUTED() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();

        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("PLATFORM_ACP_NOT_EXECUTED");
    }

    @Test
    void repeated_activate_after_successful_activation_classified_as_DUPLICATE_ACTIVATION() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);
        coordinator.activate();

        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("DUPLICATE_ACTIVATION");
    }

    @Test
    void second_prepare_with_identical_configuration_classified_as_SECOND_SDK_BUILD() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
        coordinator.prepare(config(Map.of()));

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SECOND_SDK_BUILD");
    }

    @Test
    void second_prepare_with_different_configuration_classified_as_CONFIGURATION_SPLIT_BRAIN() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
        coordinator.prepare(config(Map.of()));

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of(ExtensionPropertyNames.SAMPLING_RATIO, "0.42"))))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("CONFIGURATION_SPLIT_BRAIN");
    }

    @Test
    void failure_mid_activate_rolls_back_LIFO_and_keeps_diagnostic_MBean_with_FAILED() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        // Пре-отравление runtime-generation: сама activate() внутри вызовет registerRuntimeControls()
        // повторно и получит естественный IllegalStateException — без mock'ов и без изменения
        // production-кода ради тестируемости.
        session.jmxRegistrar().registerRuntimeControls(session.policy());

        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("ACTIVATION_FAILED");

        assertThat(session.readiness().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(session.readiness().failureCode()).isEqualTo("ACTIVATION_FAILED");
    }

    @Test
    void terminal_coordinator_repeats_same_code_for_any_subsequent_operation() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
        assertThatThrownBy(coordinator::activate);

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("PLATFORM_ACP_NOT_EXECUTED");
    }

    /**
     * Regression guard against re-splitting {@code snapshot}/{@code wire} into two independently
     * published {@code AtomicReference}s (the split-brain defect fixed alongside this test): a
     * concurrent lock-free poller must never observe a torn combination such as
     * {@code lifecycle=ACTIVE} together with {@code wire.lifecycle=INITIALIZING}.
     */
    @Test
    void currentSnapshot_never_visible_to_reader_in_inconsistent_combination_during_activate() throws InterruptedException {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
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

        coordinator.activate();
        pollerRunning.set(false);
        poller.join(5_000);

        Set<String> validPairs = Set.of("NEW/INITIALIZING", "ACTIVATING/INITIALIZING", "ACTIVE/READY");
        assertThat(observedPairs).isSubsetOf(validPairs);
        assertThat(coordinator.currentSnapshot().lifecycle()).isEqualTo("ACTIVE");
        assertThat(coordinator.currentSnapshot().wire().lifecycle()).isEqualTo("READY");
    }

    @Test
    void missing_processor_acknowledgement_fails_activation_and_never_publishes_READY() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
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
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
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
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
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
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
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
        BootstrapCoordinator coordinator = BootstrapCoordinator.create(
                ScrubbingRuntimeTestFixtures.factory(List::of));

        assertThatThrownBy(() -> coordinator.prepare(emptyBuiltInRulesConfig()))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_RULE_SET_EMPTY");
    }

    @Test
    void fail_fast_missing_hmac_key_preserves_SANITIZER_HMAC_INITIALIZATION_FAILED() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of(
                ExtensionPropertyNames.SCRUBBING_MISSING_KEY_POLICY, "fail-fast"))))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_HMAC_INITIALIZATION_FAILED");
    }

    @Test
    void rule_source_failure_preserves_SANITIZER_RULE_LOADING_FAILED() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create(ScrubbingRuntimeTestFixtures.factory(() -> {
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
        BootstrapCoordinator coordinator = BootstrapCoordinator.create(
                ScrubbingRuntimeTestFixtures.factory(() -> List.of(brokenMetadata)));

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_RULE_METADATA_INVALID");
    }

    @Test
    void linkage_error_from_rule_source_is_not_classified_as_a_sanitizer_failure() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create(ScrubbingRuntimeTestFixtures.factory(() -> {
            throw new NoClassDefFoundError("simulated missing SPI class");
        }));

        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(NoClassDefFoundError.class)
                .hasMessage("simulated missing SPI class");
    }

    @Test
    void execute_preserves_SANITIZER_FOREIGN_SAFE_EXPORTER_and_fails_readiness() {
        BootstrapCoordinator coordinator = BootstrapCoordinator.create();
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
     * Simulates the customizer chain that a real {@code AutoConfiguredOpenTelemetrySdk} build
     * drives ({@code BootstrapAutoConfigurationCustomizerProvider}) so that {@code activate()}'s
     * readiness completeness gate (installation barrier + {@code requireReady()}) is satisfied
     * without depending on the real SDK build, per this test class's stated isolation goal.
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

    private static ConfigProperties config(Map<String, String> data) {
        return new MapConfigProperties(data);
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
}
