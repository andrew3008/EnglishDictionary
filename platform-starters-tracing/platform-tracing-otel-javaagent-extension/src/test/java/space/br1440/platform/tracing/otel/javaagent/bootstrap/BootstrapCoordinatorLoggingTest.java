package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorAssert.assertThatCoordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_READY;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.invalidValidationLevelConfig;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.registerForeignReadinessMBean;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.CONFIGURATION_INITIALIZATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_SAMPLER_REPLACED;

import java.util.Map;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.ScrubberFactoryTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapCoordinatorLoggingTest {

    private static final String BOOTSTRAP_PHASE_FAILED = BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED.name();

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void successful_activation_emits_exactly_one_installed_marker() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);

            // when
            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                coordinator.activate(tracerProvider);
                assertThatThrownBy(() -> coordinator.activate(tracerProvider))
                        .isInstanceOf(BootstrapException.class);
            }

            // then
            assertThat(logs.events())
                    .filteredOn(event -> AgentEventCode.BOOT_INSTALLED.equals(event.eventCode()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsEntry("lifecycle", WIRE_READY)
                            .containsEntry("profile", "platform-agent-secure-v1")
                            .containsEntry("protocolVersion", "1")
                            .containsEntry("extensionVersion", session.readiness().snapshot().extensionVersion()));
        }
    }

    @Test
    void failed_activation_does_not_emit_installed_marker() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();

        try (BootstrapCoordinator coordinator = coordinator().server(server).build()) {
            AutoconfigureSession session = coordinator.prepare(config());

            // when
            try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
                assertBootstrapFailure(() -> coordinator.activate(tracerProvider), PLATFORM_SAMPLER_REPLACED);
            }

            // then
            assertThat(coordinator.currentWire().failureCode()).isEqualTo(PLATFORM_SAMPLER_REPLACED);
            assertThat(session.readiness().snapshot()
                    .has(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED)).isFalse();
            assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isFalse();
            assertThat(logs.events())
                    .noneMatch(event -> AgentEventCode.BOOT_INSTALLED.equals(event.eventCode()));
        }
    }

    @Test
    void diagnostic_registration_failure_is_suppressed_on_primary_configuration_cause() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        registerForeignReadinessMBean(server);

        try (BootstrapCoordinator coordinator = coordinator().server(server).build()) {
            // when
            BootstrapException first = catchBootstrapFailure(
                    () -> coordinator.prepare(invalidValidationLevelConfig()),
                    CONFIGURATION_INITIALIZATION_FAILED
            );
            BootstrapException repeated = catchBootstrapFailure(
                    () -> coordinator.prepare(invalidValidationLevelConfig()),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // then
            assertThat(first.getCause().getSuppressed())
                    .singleElement()
                    .satisfies(suppressed -> assertThat(suppressed).hasMessageContaining("ownership collision"));
            assertThat(repeated.getCause()).isSameAs(first.getCause());
            assertThat(repeated.getCause().getSuppressed())
                    .containsExactly(first.getCause().getSuppressed()[0]);
            assertThatCoordinator(coordinator).isTerminallyFailedWith(CONFIGURATION_INITIALIZATION_FAILED);
            assertThat(logs.events())
                    .filteredOn(event -> BOOTSTRAP_PHASE_FAILED.equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.throwableDigest())
                            .contains(first.getCause().getClass().getName()));
            assertThat(logs.rendered()).doesNotContain("ownership collision");
        }
    }

    @Test
    void known_configuration_failure_logs_property_name_without_property_value() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            // when
            assertBootstrapFailure(
                    () -> coordinator.prepare(config(Map.of(ExtensionPropertyNames.SCRUBBING_ENABLED, "false"))),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // then
            assertThat(logs.events())
                    .filteredOn(event -> BOOTSTRAP_PHASE_FAILED.equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsEntry("failureCode", CONFIGURATION_INITIALIZATION_FAILED)
                            .containsEntry("phase", "prepare")
                            .containsEntry("subphase", "configuration-validation")
                            .containsEntry("propertyName", ExtensionPropertyNames.SCRUBBING_ENABLED)
                            .doesNotContainValue("false"));
            assertThat(logs.rendered()).doesNotContain("=false");
        }
    }

    @Test
    void unknown_resource_validation_mode_fails_preparation_with_property_name() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.prepare(config(Map.of(
                            ExtensionPropertyNames.RESOURCE_VALIDATION_MODE,
                            "NOPE"
                    ))),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // then
            assertThatCoordinator(coordinator).isTerminallyFailedWith(CONFIGURATION_INITIALIZATION_FAILED);
            assertThat(failure.getCause())
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE);
            assertThat(logs.events())
                    .filteredOn(event -> BOOTSTRAP_PHASE_FAILED.equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsEntry("failureCode", CONFIGURATION_INITIALIZATION_FAILED)
                            .containsEntry("phase", "prepare")
                            .containsEntry("subphase", "configuration-validation")
                            .containsEntry("propertyName", ExtensionPropertyNames.RESOURCE_VALIDATION_MODE)
                            .doesNotContainValue("NOPE"));
            assertThat(logs.rendered()).doesNotContain("NOPE");
        }
    }

    @Test
    void foreign_sampler_name_fails_preparation() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.prepare(config(Map.of("otel.traces.sampler", "always_on"))),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // then
            assertThatCoordinator(coordinator).isTerminallyFailedWith(CONFIGURATION_INITIALIZATION_FAILED);
            assertThat(logs.events())
                    .filteredOn(event -> BOOTSTRAP_PHASE_FAILED.equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsEntry("failureCode", CONFIGURATION_INITIALIZATION_FAILED)
                            .containsEntry("phase", "prepare")
                            .containsEntry("subphase", "configuration-validation")
                            .containsEntry("propertyName", "otel.traces.sampler"));
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                assertThat(cause.getMessage()).doesNotContain("always_on");
            }
            assertThat(logs.rendered()).doesNotContain("always_on");
        }
    }

    @Test
    void rule_source_failure_preserves_SANITIZER_RULE_LOADING_FAILED() {
        // given
        IllegalStateException sourceFailure = new IllegalStateException("rule source unavailable");

        try (BootstrapCoordinator coordinator = coordinator()
                .scrubberFactory(ScrubberFactoryTestFixtures.factory(() -> {
                    throw sourceFailure;
                }))
                .build()) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.prepare(config()),
                    SanitizerBootstrapException.RULE_LOADING_FAILED
            );

            // then
            assertThat(failure.failure().cause()).isInstanceOf(SanitizerBootstrapException.class);
            assertThat(failure.failure().cause().getCause()).isSameAs(sourceFailure);
            assertThat(logs.events())
                    .filteredOn(event -> "Scrubbing rule collection failed".equals(event.message()))
                    .first()
                    .satisfies(event -> assertThat(event.throwableDigest())
                            .contains(IllegalStateException.class.getName()));
        }
    }
}
