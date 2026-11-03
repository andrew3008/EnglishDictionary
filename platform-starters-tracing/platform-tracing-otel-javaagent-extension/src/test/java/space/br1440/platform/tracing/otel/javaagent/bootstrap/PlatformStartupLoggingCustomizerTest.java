package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.AGENT_STARTUP_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrarTestSupport;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingTestSupport;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.javaagent.tooling.LoggingCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformStartupLoggingCustomizerTest {

    private static final String CUSTOMIZATION_FAILED = "SPAN_PROCESSOR_INITIALIZATION_FAILED";

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @BeforeEach
    void applyDefaultLoggingLevel() {
        logs.applyLevel(AgentLogLevel.INFO);
    }

    @Test
    void public_provider_exposes_platform_name_without_emitting_logs() {
        // given
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer();

        // when
        String name = customizer.name();

        // then
        assertThat(name).isEqualTo("space.br1440.platform");
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void constructor_init_and_success_do_not_access_coordinator_or_delegate_success() {
        // given
        AtomicInteger coordinatorLookups = new AtomicInteger();
        AtomicInteger providerLookups = new AtomicInteger();
        LoggingCustomizer simple = namedProvider("simple");
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    coordinatorLookups.incrementAndGet();
                    throw new AssertionError("Unexpected coordinator lookup");
                },
                () -> {
                    providerLookups.incrementAndGet();
                    return List.of(simple);
                }
        );
        assertThat(coordinatorLookups).hasValue(0);
        assertThat(providerLookups).hasValue(0);

        // when
        customizer.init();
        customizer.onStartupSuccess();

        // then
        assertThat(coordinatorLookups).hasValue(0);
        assertThat(providerLookups).hasValue(1);
        verify(simple).init();
        verify(simple, never()).onStartupSuccess();
        verify(simple, never()).onStartupFailure(any());
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void init_rejects_missing_simple_provider() {
        // given
        LoggingCustomizer other = namedProvider("none");
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    throw new AssertionError("Unexpected coordinator lookup");
                },
                () -> List.of(other)
        );

        // when
        // then
        assertThatThrownBy(customizer::init).isInstanceOf(IllegalStateException.class);
        verify(other, never()).init();
    }

    @Test
    void init_rejects_duplicate_simple_providers_without_initializing_them() {
        // given
        LoggingCustomizer first = namedProvider("simple");
        LoggingCustomizer second = namedProvider("simple");
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    throw new AssertionError("Unexpected coordinator lookup");
                },
                () -> List.of(first, second)
        );

        // when
        // then
        assertThatThrownBy(customizer::init).isInstanceOf(IllegalStateException.class);
        verify(first, never()).init();
        verify(second, never()).init();
    }

    @Test
    void init_propagates_provider_supplier_failure_without_coordinator_lookup() {
        // given
        ServiceConfigurationError failure = new ServiceConfigurationError("Logging provider discovery failed");
        AtomicInteger coordinatorLookups = new AtomicInteger();
        AtomicInteger providerLookups = new AtomicInteger();
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    coordinatorLookups.incrementAndGet();
                    throw new AssertionError("Unexpected coordinator lookup");
                },
                () -> {
                    providerLookups.incrementAndGet();
                    throw failure;
                }
        );

        // when
        Throwable outcome = catchThrowable(customizer::init);

        // then
        assertThat(outcome).isSameAs(failure);
        assertThat(coordinatorLookups).hasValue(0);
        assertThat(providerLookups).hasValue(1);
        assertThat(logs.events()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void init_propagates_lazy_discovery_failure_before_initializing_simple(boolean discoverSimple) {
        // given
        ServiceConfigurationError failure = new ServiceConfigurationError("Lazy logging provider discovery failed");
        LoggingCustomizer simple = namedProvider("simple");
        AtomicInteger coordinatorLookups = new AtomicInteger();
        Iterable<LoggingCustomizer> providers = () -> new Iterator<>() {
            private boolean delivered;

            @Override
            public boolean hasNext() {
                if (!discoverSimple || delivered) {
                    throw failure;
                }

                return true;
            }

            @Override
            public LoggingCustomizer next() {
                delivered = true;
                return simple;
            }
        };
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    coordinatorLookups.incrementAndGet();
                    throw new AssertionError("Unexpected coordinator lookup");
                },
                () -> providers
        );

        // when
        Throwable outcome = catchThrowable(customizer::init);

        // then
        assertThat(outcome).isSameAs(failure);
        assertThat(coordinatorLookups).hasValue(0);
        if (discoverSimple) {
            verify(simple).name();
        } else {
            verify(simple, never()).name();
        }
        verify(simple, never()).init();
        verify(simple, never()).onStartupFailure(any());
        assertThat(logs.events()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("simpleInitFailures")
    void simple_init_failure_reaches_new_callback_without_prepare_or_fallback(Throwable failure) {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        int initialMBeanCount = server.getMBeanCount();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        LoggingCustomizer simple = namedProvider("simple");
        LoggingCustomizer other = namedProvider("none");
        doThrow(failure).when(simple).init();
        AtomicInteger coordinatorLookups = new AtomicInteger();
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    coordinatorLookups.incrementAndGet();
                    return coordinator;
                },
                () -> List.of(simple, other)
        );

        try (coordinator) {
            // when
            Throwable initOutcome = catchThrowable(customizer::init);

            // then
            assertThat(initOutcome).isSameAs(failure);
            assertThat(coordinatorLookups).hasValue(0);
            verify(simple).init();
            verify(other, never()).init();
            verify(simple, never()).onStartupFailure(any());
            assertThat(coordinator.state()).isEqualTo(BootstrapState.NEW);
            assertThat(server.getMBeanCount()).isEqualTo(initialMBeanCount);
            assertThat(logs.events()).isEmpty();

            // when
            Throwable callbackOutcome = catchThrowable(() -> customizer.onStartupFailure(initOutcome));

            // then
            if (failure instanceof LinkageError) {
                assertThat(callbackOutcome).isSameAs(failure);
            } else {
                assertThat(callbackOutcome).isNull();
            }
            assertThat(coordinatorLookups).hasValue(1);
            assertThat(coordinator.state()).isEqualTo(BootstrapState.NEW);
            assertThat(coordinator.diagnosticSnapshot().failure()).isNull();
            assertThat(server.getMBeanCount()).isEqualTo(initialMBeanCount);
            assertThatDiagnosticMBean(server).isNotRegistered();
            CapturedLogEvent summary = startupSummary();
            assertThat(summary.errorType()).isEqualTo(failure.getClass().getName());
            assertThat(summary.fields())
                    .containsEntry("lifecycle", "NEW")
                    .doesNotContainKeys("failureCode", "primaryFailureCode");
            assertThat(logs.rendered()).doesNotContain("simple-init-secret");
            verify(simple).init();
            verify(simple, never()).onStartupFailure(any());
            verify(other, never()).init();
            verify(other, never()).onStartupFailure(any());
            verifyNoInteractions(runtime);
        }
    }

    @Test
    void prepared_startup_failure_publishes_failed_diagnostic_and_one_error_summary() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        ConfigurationException cause = new ConfigurationException("Invalid OTLP endpoint");
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            coordinator.prepare(config());
            PlatformStartupLoggingCustomizer customizer = customizer(coordinator);

            // when
            customizer.onStartupFailure(cause);

            // then
            assertThatDiagnosticMBean(server).isFailedWith(AGENT_STARTUP_FAILED);
            assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(AGENT_STARTUP_FAILED);
            CapturedLogEvent summary = startupSummary();
            assertThat(summary.fields())
                    .containsEntry("lifecycle", "FAILED")
                    .containsEntry("failureCode", AGENT_STARTUP_FAILED)
                    .containsEntry("primaryFailureCode", AGENT_STARTUP_FAILED);
            assertThat(summary.errorType()).isEqualTo(ConfigurationException.class.getName());
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.eventName()).isEqualTo(BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED.name());
                assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
            });
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void repeated_startup_callback_keeps_terminal_failure_and_emits_each_cause() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        ConfigurationException first = new ConfigurationException("First startup failure");
        IllegalStateException second = new IllegalStateException("Repeated startup failure");

        try (coordinator) {
            coordinator.prepare(config());
            PlatformStartupLoggingCustomizer customizer = customizer(coordinator);

            // when
            customizer.onStartupFailure(first);
            BootstrapDiagnosticSnapshot diagnostics = coordinator.diagnosticSnapshot();
            ExtensionReadinessWireSnapshot failed = coordinator.currentWire();
            customizer.onStartupFailure(second);

            // then
            assertThat(diagnostics.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.diagnosticSnapshot().failure()).isSameAs(diagnostics.failure());
            assertThat(diagnostics.failure().cause()).isSameAs(first);
            assertThat(diagnostics.failure().primaryCode()).isEqualTo(AGENT_STARTUP_FAILED);
            assertThat(diagnostics.failure().effectiveCode()).isEqualTo(AGENT_STARTUP_FAILED);
            assertThat(coordinator.currentWire()).isSameAs(failed);
            assertThatDiagnosticMBean(server).isFailedWith(AGENT_STARTUP_FAILED);
            assertThat(first.getSuppressed()).isEmpty();
            assertThat(second.getSuppressed()).isEmpty();
            List<CapturedLogEvent> summaries = logs.events().stream()
                    .filter(event -> BootstrapLogEvent.STARTUP_FAILED.name().equals(event.eventName()))
                    .toList();
            assertThat(summaries).hasSize(2).allSatisfy(summary -> {
                assertThat(summary.level()).isEqualTo(AgentLogLevel.ERROR);
                assertThat(summary.eventCode()).isEqualTo(AgentEventCode.BOOT_STARTUP_FAILED);
                assertThat(summary.fields())
                        .containsEntry("lifecycle", "FAILED")
                        .containsEntry("failureCode", AGENT_STARTUP_FAILED)
                        .containsEntry("primaryFailureCode", AGENT_STARTUP_FAILED);
            });
            assertThat(summaries).extracting(CapturedLogEvent::errorType)
                    .containsExactly(ConfigurationException.class.getName(), IllegalStateException.class.getName());
            assertThat(logs.events()).filteredOn(event ->
                    BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED.name().equals(event.eventName())).hasSize(1);
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void summary_retains_failure_codes_when_failed_publication_leaves_wire_initializing() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        PlatformExtensionReadiness readiness = spy(PlatformExtensionReadiness.forCurrentExtension());
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        ConfigurationException cause = new ConfigurationException("Invalid OTLP endpoint");
        IllegalStateException publicationFailure = new IllegalStateException("Failure publication failed");
        BootstrapCoordinator coordinator = coordinatorWithReadiness(server, readiness, runtime);

        try (coordinator) {
            coordinator.prepare(config());
            ExtensionReadinessWireSnapshot initializing = coordinator.currentWire();
            doThrow(publicationFailure).when(readiness).fail(anyString(), anyString());

            // when
            Throwable outcome = catchThrowable(() -> customizer(coordinator).onStartupFailure(cause));

            // then
            assertThat(outcome).isNull();
            assertThat(coordinator.currentWire()).isSameAs(initializing);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("INITIALIZING");
            BootstrapDiagnosticSnapshot diagnostics = coordinator.diagnosticSnapshot();
            assertThat(diagnostics.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(diagnostics.failure().cause()).isSameAs(cause);
            assertThat(cause.getSuppressed()).containsExactly(publicationFailure);
            assertThat(startupSummary().fields())
                    .containsEntry("lifecycle", "FAILED")
                    .containsEntry("failureCode", AGENT_STARTUP_FAILED)
                    .containsEntry("primaryFailureCode", AGENT_STARTUP_FAILED);
            assertThatDiagnosticMBean(server).isRegistered();
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void summary_uses_escalated_failure_when_cleanup_publication_leaves_wire_with_original_code() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        PlatformExtensionReadiness readiness = spy(PlatformExtensionReadiness.forCurrentExtension());
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        ConfigurationException cause = new ConfigurationException("Invalid OTLP endpoint");
        IllegalStateException cleanupFailure = new IllegalStateException("Scrubbing cleanup failed");
        IllegalStateException publicationFailure = new IllegalStateException("Cleanup failure publication failed");
        doThrow(cleanupFailure).when(runtime).close();
        BootstrapCoordinator coordinator = coordinatorWithReadiness(server, readiness, runtime);

        try (coordinator) {
            coordinator.prepare(config());
            doCallRealMethod().doThrow(publicationFailure).when(readiness).fail(anyString(), anyString());

            // when
            Throwable outcome = catchThrowable(() -> customizer(coordinator).onStartupFailure(cause));

            // then
            assertThat(outcome).isNull();
            assertThatDiagnosticMBean(server).isFailedWith(AGENT_STARTUP_FAILED);
            assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(AGENT_STARTUP_FAILED);
            BootstrapDiagnosticSnapshot diagnostics = coordinator.diagnosticSnapshot();
            assertThat(diagnostics.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(diagnostics.failure().cause()).isSameAs(cause);
            assertThat(cause.getSuppressed()).contains(cleanupFailure, publicationFailure);
            assertThat(startupSummary().fields())
                    .containsEntry("lifecycle", "FAILED")
                    .containsEntry("failureCode", FAILED_ROLLBACK_INCOMPLETE)
                    .containsEntry("primaryFailureCode", AGENT_STARTUP_FAILED);
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void early_startup_failure_emits_summary_without_prepare_or_mbean() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        ConfigurationException cause = new ConfigurationException("Early agent initialization failed");

        try (coordinator) {
            // when
            customizer(coordinator).onStartupFailure(cause);

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.NEW);
            assertThat(coordinator.diagnosticSnapshot().failure()).isNull();
            assertThatDiagnosticMBean(server).isNotRegistered();
            assertThat(startupSummary().fields())
                    .containsEntry("lifecycle", "NEW")
                    .doesNotContainKeys("failureCode", "primaryFailureCode");
            verifyNoInteractions(runtime);
        }
    }

    @Test
    void already_failed_startup_keeps_primary_and_cleanup_while_emitting_one_summary() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        IllegalStateException cause = new IllegalStateException("Platform customizer failed");

        try (coordinator) {
            coordinator.prepare(config());
            BootstrapException classified = catchBootstrapFailure(() -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                throw cause;
            }), CUSTOMIZATION_FAILED);
            ExtensionReadinessWireSnapshot failed = coordinator.currentWire();

            // when
            customizer(coordinator).onStartupFailure(classified);

            // then
            assertThat(coordinator.currentWire()).isSameAs(failed);
            assertThat(classified.getCause()).isSameAs(cause);
            assertThat(cause.getSuppressed()).isEmpty();
            assertThat(startupSummary().fields())
                    .containsEntry("lifecycle", "FAILED")
                    .containsEntry("failureCode", CUSTOMIZATION_FAILED)
                    .containsEntry("primaryFailureCode", CUSTOMIZATION_FAILED);
            assertThatDiagnosticMBean(server).isFailedWith(CUSTOMIZATION_FAILED);
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void activation_rollback_restores_default_logging_before_startup_summary_from_off_policy() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        AtomicReference<AgentLogLevel> levelDuringActivation = new AtomicReference<>();
        IllegalStateException activationFailure = new IllegalStateException("Installation incomplete");
        doAnswer(invocation -> {
            levelDuringActivation.set(AgentLoggingRuntime.shared().effectiveLevel());
            throw activationFailure;
        }).when(runtime).requireInstallationComplete();
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            AutoconfigureSession session = coordinator.prepare(config(Map.of(
                    ExtensionPropertyNames.DIAGNOSTICS_LOG_LEVEL, "OFF"
            )));
            try (SdkTracerProvider provider = providerFor(session)) {
                BootstrapException classified = catchBootstrapFailure(
                        () -> coordinator.activate(provider), ACTIVATION_FAILED
                );
                ExtensionReadinessWireSnapshot failed = coordinator.currentWire();

                // when
                customizer(coordinator).onStartupFailure(classified);

                // then
                assertThat(levelDuringActivation).hasValue(AgentLogLevel.OFF);
                assertThat(AgentLoggingRuntime.shared().effectiveLevel()).isEqualTo(AgentLogLevel.INFO);
                assertThat(coordinator.currentWire()).isSameAs(failed);
                assertThat(classified.getCause()).isSameAs(activationFailure);
                assertThat(startupSummary().fields())
                        .containsEntry("failureCode", ACTIVATION_FAILED)
                        .containsEntry("primaryFailureCode", ACTIVATION_FAILED);
                assertThatDiagnosticMBean(server).isFailedWith(ACTIVATION_FAILED);
                verify(runtime).close();
            }
        }

        verify(runtime).close();
    }

    @ParameterizedTest
    @MethodSource("ordinaryCleanupFailures")
    void ordinary_cleanup_failure_is_suppressed_without_escaping_callback(Throwable cleanupFailure) {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        doThrow(cleanupFailure).when(runtime).close();
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        ConfigurationException cause = new ConfigurationException("Invalid OTLP endpoint");

        try (coordinator) {
            coordinator.prepare(config());

            // when
            Throwable outcome = catchThrowable(() -> customizer(coordinator).onStartupFailure(cause));

            // then
            assertThat(outcome).isNull();
            assertThat(BootstrapCleanupFailures.contains(cause, cleanupFailure)).isTrue();
            assertThatDiagnosticMBean(server).isFailedWith(FAILED_ROLLBACK_INCOMPLETE);
            assertThat(startupSummary().fields())
                    .containsEntry("failureCode", FAILED_ROLLBACK_INCOMPLETE)
                    .containsEntry("primaryFailureCode", AGENT_STARTUP_FAILED);
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void ordinary_holder_failure_does_not_escape_or_hide_original_startup_failure() {
        // given
        ConfigurationException cause = new ConfigurationException("original-startup-secret");
        AssertionError lookupFailure = new AssertionError("holder-lookup-secret");
        cause.setStackTrace(new StackTraceElement[0]);
        lookupFailure.setStackTrace(new StackTraceElement[0]);
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    throw lookupFailure;
                },
                List::of
        );

        // when
        Throwable outcome = catchThrowable(() -> customizer.onStartupFailure(cause));

        // then
        assertThat(outcome).isNull();
        assertThat(cause.getSuppressed()).containsExactly(lookupFailure);
        CapturedLogEvent summary = startupSummary();
        assertThat(summary.errorType()).isEqualTo(ConfigurationException.class.getName());
        assertThat(summary.throwableDigest()).contains("suppressed " + AssertionError.class.getName());
        assertThat(logs.rendered()).doesNotContain("original-startup-secret", "holder-lookup-secret");
    }

    @Test
    void delivery_wrapper_referencing_original_does_not_create_suppression_cycle() {
        // given
        ConfigurationException cause = new ConfigurationException("Original startup failure");
        IllegalStateException deliveryFailure = new IllegalStateException("Startup delivery failed", cause);
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    throw deliveryFailure;
                },
                List::of
        );

        // when
        Throwable outcome = catchThrowable(() -> customizer.onStartupFailure(cause));

        // then
        assertThat(outcome).isNull();
        assertThat(cause.getSuppressed()).isEmpty();
        assertThat(deliveryFailure.getCause()).isSameAs(cause);
        assertThat(BootstrapCleanupFailures.contains(cause, deliveryFailure)).isFalse();
        assertThat(startupSummary().errorType()).isEqualTo(ConfigurationException.class.getName());
    }

    @Test
    void fatal_holder_initialization_failure_propagates_without_replacing_original_cause() {
        // given
        ConfigurationException cause = new ConfigurationException("Original startup failure");
        ExceptionInInitializerError lookupFailure = new ExceptionInInitializerError("Holder initialization failed");
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    throw lookupFailure;
                },
                List::of
        );

        // when
        Throwable outcome = catchThrowable(() -> customizer.onStartupFailure(cause));

        // then
        assertThat(outcome).isSameAs(lookupFailure);
        assertThat(cause.getSuppressed()).containsExactly(lookupFailure);
        assertThat(startupSummary().errorType()).isEqualTo(ConfigurationException.class.getName());
    }

    @Test
    void original_fatal_failure_propagates_after_delivery_and_summary() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        LinkageError cause = new LinkageError("SDK linkage failed");

        try (coordinator) {
            coordinator.prepare(config());

            // when
            Throwable outcome = catchThrowable(() -> customizer(coordinator).onStartupFailure(cause));

            // then
            assertThat(outcome).isSameAs(cause);
            assertThat(cause.getSuppressed()).isEmpty();
            assertThatDiagnosticMBean(server).isFailedWith(AGENT_STARTUP_FAILED);
            assertThat(startupSummary().errorType()).isEqualTo(LinkageError.class.getName());
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void wrapped_original_fatal_failure_propagates_after_delivery_and_summary() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        LinkageError fatal = new LinkageError("SDK linkage failed");
        ConfigurationException cause = new ConfigurationException("SDK startup failed", fatal);

        try (coordinator) {
            coordinator.prepare(config());

            // when
            Throwable outcome = catchThrowable(() -> customizer(coordinator).onStartupFailure(cause));

            // then
            assertThat(outcome).isSameAs(fatal);
            assertThat(coordinator.diagnosticSnapshot().failure().cause()).isSameAs(cause);
            assertThat(cause.getCause()).isSameAs(fatal);
            assertThatDiagnosticMBean(server).isFailedWith(AGENT_STARTUP_FAILED);
            assertThat(startupSummary().errorType()).isEqualTo(ConfigurationException.class.getName());
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void wrapped_secondary_fatal_failure_propagates_after_summary() {
        // given
        ConfigurationException cause = new ConfigurationException("Original startup failure");
        LinkageError fatal = new LinkageError("Holder linkage failed");
        IllegalStateException deliveryFailure = new IllegalStateException("Holder lookup failed", fatal);
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    throw deliveryFailure;
                },
                List::of
        );

        // when
        Throwable outcome = catchThrowable(() -> customizer.onStartupFailure(cause));

        // then
        assertThat(outcome).isSameAs(fatal);
        assertThat(cause.getSuppressed()).containsExactly(deliveryFailure);
        assertThat(deliveryFailure.getCause()).isSameAs(fatal);
        assertThat(startupSummary().errorType()).isEqualTo(ConfigurationException.class.getName());
    }

    @Test
    void original_fatal_failure_takes_precedence_over_fatal_delivery_after_summary() {
        // given
        LinkageError cause = new LinkageError("Original SDK linkage failed");
        ExceptionInInitializerError deliveryFailure = new ExceptionInInitializerError("Holder initialization failed");
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    throw deliveryFailure;
                },
                List::of
        );

        // when
        Throwable outcome = catchThrowable(() -> customizer.onStartupFailure(cause));

        // then
        assertThat(outcome).isSameAs(cause);
        assertThat(cause.getSuppressed()).containsExactly(deliveryFailure);
        assertThat(startupSummary().errorType()).isEqualTo(LinkageError.class.getName());
    }

    @Test
    void ordinary_summary_sink_failure_returns_without_retry_or_cause_replacement() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator coordinator = coordinator().server(server).build();
        ConfigurationException cause = new ConfigurationException("Original startup failure");
        IOException sinkFailure = new IOException("Summary delivery failed");
        OutputStream sink = mock(OutputStream.class);
        doThrow(sinkFailure).when(sink).write(any(byte[].class));
        logs.close();

        try (coordinator; AutoCloseable ignored = AgentLoggingTestSupport.install(sink)) {
            AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.INFO);

            // when
            Throwable outcome = catchThrowable(() -> customizer(coordinator).onStartupFailure(cause));

            // then
            assertThat(outcome).isNull();
            assertThat(cause.getSuppressed()).isEmpty();
            assertThat(cause.getCause()).isNull();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.NEW);
            assertThat(coordinator.diagnosticSnapshot().failure()).isNull();
            assertThatDiagnosticMBean(server).isNotRegistered();
            verify(sink).write(any(byte[].class));
        }
    }

    @ParameterizedTest
    @MethodSource("startupAndLoggingFailures")
    void fatal_summary_preserves_original_and_secondary_priority(Throwable cause,
                                                                Error deliveryFailure,
                                                                Error loggingFailure,
                                                                Throwable expected) throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator coordinator = coordinator().server(server).build();
        PlatformStartupLoggingCustomizer customizer = new PlatformStartupLoggingCustomizer(
                () -> {
                    if (deliveryFailure != null) {
                        throw deliveryFailure;
                    }

                    return coordinator;
                },
                List::of
        );
        OutputStream sink = mock(OutputStream.class);
        doThrow(loggingFailure).when(sink).write(any(byte[].class));
        logs.close();

        try (coordinator; AutoCloseable ignored = AgentLoggingTestSupport.install(sink)) {
            AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.INFO);

            // when
            Throwable outcome = catchThrowable(() -> customizer.onStartupFailure(cause));

            // then
            assertThat(outcome).isSameAs(expected);
            if (deliveryFailure == null) {
                assertThat(cause.getSuppressed()).containsExactly(loggingFailure);
            } else {
                assertThat(cause.getSuppressed()).containsExactly(deliveryFailure, loggingFailure);
            }
            assertThat(cause.getCause()).isNull();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.NEW);
            assertThat(coordinator.diagnosticSnapshot().failure()).isNull();
            assertThatDiagnosticMBean(server).isNotRegistered();
            verify(sink).write(any(byte[].class));
        }
    }

    @Test
    void simulated_original_vm_error_before_prepare_propagates_after_summary() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator coordinator = coordinator().server(server).build();
        OutOfMemoryError cause = new OutOfMemoryError("Simulated startup memory failure");

        try (coordinator) {
            // when
            Throwable outcome = catchThrowable(() -> customizer(coordinator).onStartupFailure(cause));

            // then
            assertThat(outcome).isSameAs(cause);
            assertThat(coordinator.state()).isEqualTo(BootstrapState.NEW);
            assertThat(coordinator.diagnosticSnapshot().failure()).isNull();
            assertThatDiagnosticMBean(server).isNotRegistered();
            assertThat(startupSummary().errorType()).isEqualTo(OutOfMemoryError.class.getName());
        }
    }

    @Test
    void throwing_get_cause_does_not_escape_startup_callback() {
        // given
        RuntimeException cause = new RuntimeException("Startup failure with inaccessible cause") {
            @Override
            public synchronized Throwable getCause() {
                throw new AssertionError("Cause inspection failed");
            }
        };
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            // when
            Throwable outcome = catchThrowable(() -> customizer(coordinator).onStartupFailure(cause));

            // then
            assertThat(outcome).isNull();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.NEW);
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.level()).isEqualTo(AgentLogLevel.ERROR);
                assertThat(BootstrapLogEvent.STARTUP_FAILED.name().equals(event.eventName())
                        || BootstrapLogEvent.STARTUP_FAILED.name().equals(event.originalEventName())).isTrue();
            });
        }
    }

    @Test
    void interrupted_original_cause_restores_interrupt_without_creating_readiness() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator coordinator = coordinator().server(server).build();
        InterruptedException cause = new InterruptedException("Startup interrupted");

        try (coordinator) {
            // when
            assertThatCode(() -> customizer(coordinator).onStartupFailure(cause)).doesNotThrowAnyException();

            // then
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThatDiagnosticMBean(server).isNotRegistered();
            assertThat(startupSummary().errorType()).isEqualTo(InterruptedException.class.getName());
        } finally {
            //noinspection ResultOfMethodCallIgnored -- очищаем флаг, чтобы не влиять на следующие тесты
            Thread.interrupted();
        }
    }

    private CapturedLogEvent startupSummary() {
        List<CapturedLogEvent> summaries = logs.events().stream()
                .filter(event -> BootstrapLogEvent.STARTUP_FAILED.name().equals(event.eventName()))
                .toList();
        assertThat(summaries).hasSize(1);
        CapturedLogEvent summary = summaries.getFirst();
        assertThat(summary.level()).isEqualTo(AgentLogLevel.ERROR);
        assertThat(summary.eventCode()).isEqualTo(AgentEventCode.BOOT_STARTUP_FAILED);
        assertThat(summary.eventCode().code()).isEqualTo("PTA-BOOT-012");
        return summary;
    }

    private static PlatformStartupLoggingCustomizer customizer(BootstrapCoordinator coordinator) {
        return new PlatformStartupLoggingCustomizer(() -> coordinator, List::of);
    }

    private static LoggingCustomizer namedProvider(String name) {
        LoggingCustomizer provider = mock(LoggingCustomizer.class);
        doReturn(name).when(provider).name();
        return provider;
    }

    private static PlatformScrubberFactory factory(ScrubbingRuntime runtime) {
        PlatformScrubberFactory factory = mock(PlatformScrubberFactory.class);
        //noinspection resource -- настройка Mockito не создаёт ресурс; runtime закрывает координатор
        doReturn(runtime).when(factory).buildRuntime(any());
        return factory;
    }

    private static BootstrapCoordinator coordinatorWithReadiness(MBeanServer server,
                                                                 PlatformExtensionReadiness readiness,
                                                                 ScrubbingRuntime runtime) {
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = PlatformTracingMBeanRegistrarTestSupport.create(
                server, readiness, agentInstanceId
        );
        return coordinator()
                .agentInstanceId(agentInstanceId)
                .registrar(registrar)
                .scrubberFactory(factory(runtime))
                .build();
    }

    private static Stream<Throwable> ordinaryCleanupFailures() {
        return Stream.of(
                new IllegalStateException("Scrubbing cleanup failed"),
                new AssertionError("Scrubbing cleanup assertion failed")
        );
    }

    private static Stream<Throwable> simpleInitFailures() {
        return Stream.of(
                new IllegalStateException("simple-init-secret"),
                new LinkageError("simple-init-secret")
        );
    }

    private static Stream<Arguments> startupAndLoggingFailures() {
        LinkageError originalFatal = new LinkageError("Original startup linkage failed");
        LinkageError loggingAfterOriginalFatal = new LinkageError("Summary linkage failed");
        ConfigurationException ordinary = new ConfigurationException("Original startup failure");
        LinkageError loggingAfterOrdinary = new LinkageError("Summary linkage failed");
        ConfigurationException ordinaryWithDelivery = new ConfigurationException("Original startup failure");
        ExceptionInInitializerError deliveryAfterOrdinary = new ExceptionInInitializerError("Holder initialization failed");
        LinkageError loggingAfterDelivery = new LinkageError("Summary linkage failed");
        LinkageError originalWithDelivery = new LinkageError("Original startup linkage failed");
        ExceptionInInitializerError deliveryAfterOriginalFatal = new ExceptionInInitializerError("Holder initialization failed");
        LinkageError loggingAfterBothFatal = new LinkageError("Summary linkage failed");
        return Stream.of(
                Arguments.of(originalFatal, null, loggingAfterOriginalFatal, originalFatal),
                Arguments.of(ordinary, null, loggingAfterOrdinary, loggingAfterOrdinary),
                Arguments.of(ordinaryWithDelivery, deliveryAfterOrdinary, loggingAfterDelivery, loggingAfterDelivery),
                Arguments.of(originalWithDelivery, deliveryAfterOriginalFatal, loggingAfterBothFatal, originalWithDelivery)
        );
    }
}
