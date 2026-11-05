package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_INITIALIZING;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_READY;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureCodes.PLATFORM_SAMPLER_REPLACED;
import static space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationExceptionFixtures.rollbackIncomplete;

import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapException;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailure;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingTestSupport;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapCoordinatorActivationFailureTest {

    @Test
    void fatal_activation_cause_wins_over_fatal_cleanup_and_keeps_cleanup_attached() {
        // given
        NoClassDefFoundError activationFailure = new NoClassDefFoundError("Fatal activation failure");
        LinkageError cleanupFailure = new LinkageError("Fatal runtime cleanup failure");
        Fixture fixture = fixture(activationFailure, failingRegistration(cleanupFailure));

        try (fixture) {
            // when
            Throwable thrown = fixture.activate();

            // then
            assertThat(thrown).isSameAs(activationFailure);
            assertThat(activationFailure.getSuppressed()).containsExactly(cleanupFailure);
            assertThat(cleanupFailure.getSuppressed()).isEmpty();
            assertThat(fixture.failure().primaryCode())
                    .isEqualTo(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
            assertThat(fixture.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            assertThat(fixture.failure().cause()).isSameAs(activationFailure);
            assertThat(fixture.coordinator().currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        }
    }

    @Test
    void fatal_activation_cause_wins_over_failure_publication_error() {
        // given
        NoClassDefFoundError activationFailure = new NoClassDefFoundError("Fatal activation failure");
        UnsupportedOperationException publicationFailure =
                new UnsupportedOperationException("Failed readiness publication");
        Fixture fixture = fixture(activationFailure, succeedingRegistration());
        fixture.failPublications(publicationFailure);

        try (fixture) {
            // when
            Throwable thrown = fixture.activate();

            // then
            assertThat(thrown).isSameAs(activationFailure);
            assertThat(activationFailure.getSuppressed()).containsExactly(publicationFailure);
            assertThat(fixture.coordinator().state()).isEqualTo(BootstrapState.FAILED);
            assertThat(fixture.failure().cause()).isSameAs(activationFailure);
            assertThat(fixture.publicationCalls()).hasValue(1);
        }
    }

    @Test
    void publication_failure_with_cleanup_failure_throws_escalated_failure() {
        // given
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        IllegalStateException cleanupFailure = new IllegalStateException("Runtime controls cleanup failed");
        UnsupportedOperationException publicationFailure =
                new UnsupportedOperationException("Failed readiness publication");
        Fixture fixture = fixture(activationFailure, failingRegistration(cleanupFailure));
        fixture.failPublications(publicationFailure);

        try (fixture) {
            // when
            Throwable thrown = fixture.activate();

            // then
            assertThat(thrown).isInstanceOf(BootstrapException.class);
            BootstrapException failure = (BootstrapException) thrown;
            assertThat(failure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
            assertThat(failure.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            assertThat(failure.getCause()).isSameAs(activationFailure);
            assertThat(BootstrapCleanupFailures.contains(activationFailure, cleanupFailure)).isTrue();
            assertThat(activationFailure.getSuppressed()).startsWith(publicationFailure);
            assertThat(fixture.publicationCalls()).hasValue(2);
            assertThat(fixture.coordinator().currentWire().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
            assertThat(fixture.coordinator().currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        }
    }

    @Test
    void first_fatal_publication_failure_wins_over_later_fatal_cleanup() {
        // given
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        LinkageError publicationFailure = new LinkageError("Fatal readiness publication failure");
        LinkageError cleanupFailure = new LinkageError("Fatal runtime cleanup failure");
        Fixture fixture = fixture(activationFailure, failingRegistration(cleanupFailure));
        fixture.failPublications(publicationFailure);

        try (fixture) {
            // when
            Throwable thrown = fixture.activate();

            // then
            assertThat(thrown).isSameAs(publicationFailure);
            assertThat(activationFailure.getSuppressed()).containsExactly(publicationFailure, cleanupFailure);
            assertThat(publicationFailure.getSuppressed()).isEmpty();
            assertThat(fixture.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
            assertThat(fixture.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            assertThat(fixture.coordinator().currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        }
    }

    @Test
    void first_fatal_publication_failure_wins_over_later_escalation_publication_failure() {
        // given
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        LinkageError publicationFailure = new LinkageError("Fatal readiness publication failure");
        LinkageError cleanupFailure = new LinkageError("Fatal runtime cleanup failure");
        LinkageError escalationPublicationFailure = new LinkageError("Fatal escalation publication failure");
        Fixture fixture = fixture(activationFailure, failingRegistration(cleanupFailure));
        fixture.failPublications(publicationFailure, escalationPublicationFailure);

        try (fixture) {
            // when
            Throwable thrown = fixture.activate();

            // then
            assertThat(thrown).isSameAs(publicationFailure);
            assertThat(activationFailure.getSuppressed())
                    .containsExactly(publicationFailure, cleanupFailure, escalationPublicationFailure);
            assertThat(publicationFailure.getSuppressed()).isEmpty();
            assertThat(fixture.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
            assertThat(fixture.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        }
    }

    @Test
    void rollback_marker_escalates_without_mutating_primary_cause_suppressed() {
        // given
        Throwable activationFailure = rollbackIncomplete(
                "Runtime MBean rollback was incomplete",
                new IllegalStateException("Runtime MBean failure")
        );
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(
                MBeanServerFactory.newMBeanServer(),
                AgentInstanceId.random()
        ));
        //noinspection resource — stubbing registerRuntimeControls не создаёт регистрацию
        doThrow(activationFailure).when(registrar).registerRuntimeControls(any());
        BootstrapCoordinator coordinator = coordinator().registrar(registrar).build();

        try {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);

            // when
            Throwable thrown;
            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                thrown = catchThrowable(() -> coordinator.activate(tracerProvider));
            }

            // then
            assertThat(thrown).isInstanceOf(BootstrapException.class);
            BootstrapException failure = (BootstrapException) thrown;
            assertThat(failure.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            assertThat(failure.getCause()).isSameAs(activationFailure);
            assertThat(activationFailure.getSuppressed()).isEmpty();
            assertThat(coordinator.currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        } finally {
            closeQuietly(coordinator);
        }
    }

    @Test
    void fatal_settlement_log_failure_stops_further_logs_and_is_thrown() throws Exception {
        // given
        IllegalStateException cleanupFailure = new IllegalStateException("Session cleanup failed");
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        doThrow(cleanupFailure).when(scrubbingRuntime).close();
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        //noinspection resource -- stubbing buildRuntime не открывает runtime; экземпляр закрывает AutoconfigureSession.close()
        doReturn(scrubbingRuntime).when(scrubberFactory).buildRuntime(any());
        BootstrapCoordinator coordinator = coordinator().scrubberFactory(scrubberFactory).build();
        LinkageError loggerFailure = new LinkageError("Fatal log delivery failure");
        FailingOutputStream stream = new FailingOutputStream(loggerFailure);

        try (SdkTracerProvider foreignProvider = SdkTracerProvider.builder().build()) {
            coordinator.prepare(config());
            AgentLoggingTestSupport.reset();

            // when
            Throwable thrown;
            try (AutoCloseable ignored = AgentLoggingTestSupport.install(stream)) {
                thrown = catchThrowable(() -> coordinator.activate(foreignProvider));
            }

            // then
            assertThat(thrown).isSameAs(loggerFailure);
            assertThat(stream.writeAttempts()).isEqualTo(1);
            BootstrapFailure failure = coordinator.diagnosticSnapshot().failure();
            assertThat(failure).isNotNull();
            assertThat(failure.primaryCode()).isEqualTo(PLATFORM_SAMPLER_REPLACED);
            assertThat(failure.effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            assertThat(BootstrapCleanupFailures.contains(failure.cause(), cleanupFailure)).isTrue();
            assertThat(failure.cause().getSuppressed()).contains(loggerFailure);
            assertThat(loggerFailure.getSuppressed()).isEmpty();
        } finally {
            closeQuietly(coordinator);
        }
    }

    @Test
    void failure_before_ready_with_failed_publication_leaves_wire_initializing() {
        // given
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        UnsupportedOperationException publicationFailure =
                new UnsupportedOperationException("Failed readiness publication");
        Fixture fixture = fixture(activationFailure, succeedingRegistration());
        fixture.failPublications(publicationFailure);

        try (fixture) {
            // when
            Throwable thrown = fixture.activate();

            // then
            assertThat(thrown).isInstanceOf(BootstrapException.class);
            assertThat(fixture.coordinator().state()).isEqualTo(BootstrapState.FAILED);
            assertThat(fixture.coordinator().currentWire().lifecycle()).isEqualTo(WIRE_INITIALIZING);
            assertThat(fixture.failure().cause()).isSameAs(activationFailure);
            assertThat(activationFailure.getSuppressed()).containsExactly(publicationFailure);
        }
    }

    @Test
    void fatal_installed_marker_failure_after_ready_keeps_active_runtime_and_is_not_admitted_as_startup_failure()
            throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator coordinator = coordinator().server(server).build();
        LinkageError markerFailure = new LinkageError("Fatal installed marker delivery failure");
        FailingOutputStream stream = new FailingOutputStream(markerFailure);
        PlatformStartupLoggingCustomizer startupAdapter = new PlatformStartupLoggingCustomizer(() -> coordinator, List::of);

        try {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);
            AgentLoggingTestSupport.reset();

            // when
            Throwable activationOutcome;
            try (SdkTracerProvider tracerProvider = providerFor(session);
                 AutoCloseable ignored = AgentLoggingTestSupport.install(stream)) {
                activationOutcome = catchThrowable(() -> coordinator.activate(tracerProvider));
            }
            Throwable adapterOutcome = catchThrowable(() -> startupAdapter.onStartupFailure(activationOutcome));

            // then
            assertThat(activationOutcome).isSameAs(markerFailure);
            assertThat(adapterOutcome).isSameAs(markerFailure);
            assertThat(stream.writeAttempts()).isEqualTo(1);
            assertThat(coordinator.state()).isEqualTo(BootstrapState.ACTIVE);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_READY);
            assertThat(coordinator.diagnosticSnapshot().failure()).isNull();
            assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
            assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isTrue();
            assertThat(markerFailure.getSuppressed()).isEmpty();
        } finally {
            closeQuietly(coordinator);
        }
    }

    private static Fixture fixture(Throwable activationFailure,
                                   PlatformTracingMBeanRegistrar.RuntimeRegistration registration) {
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(
                MBeanServerFactory.newMBeanServer(),
                AgentInstanceId.random()
        ));
        //noinspection resource — регистрацию закрывает координатор
        doReturn(registration).when(registrar).registerRuntimeControls(any());
        PlatformExtensionReadiness readiness = spy(registrar.extensionReadiness());
        doReturn(readiness).when(registrar).extensionReadiness();
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        doThrow(activationFailure).when(scrubbingRuntime).requireInstallationComplete();
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        //noinspection resource -- stubbing buildRuntime не открывает runtime; экземпляр закрывает AutoconfigureSession.close()
        doReturn(scrubbingRuntime).when(scrubberFactory).buildRuntime(any());
        BootstrapCoordinator coordinator = coordinator()
                .registrar(registrar)
                .scrubberFactory(scrubberFactory)
                .build();
        return new Fixture(coordinator, readiness, new AtomicInteger());
    }

    private static PlatformTracingMBeanRegistrar.RuntimeRegistration failingRegistration(Throwable cleanupFailure) {
        return new TestRuntimeRegistration(cleanupFailure);
    }

    private static PlatformTracingMBeanRegistrar.RuntimeRegistration succeedingRegistration() {
        return new TestRuntimeRegistration(null);
    }

    private static void closeQuietly(BootstrapCoordinator coordinator) {
        try {
            coordinator.close();
        } catch (Throwable ignored) {
            // повторное закрытие после обработки отказа не должно подменять отказ теста
        }
    }

    private record Fixture(BootstrapCoordinator coordinator,
                           PlatformExtensionReadiness readiness,
                           AtomicInteger publicationCalls) implements AutoCloseable {

        private void failPublications(Throwable... failures) {
            doAnswer(invocation -> {
                int call = publicationCalls.getAndIncrement();
                if (call < failures.length) {
                    throw failures[call];
                }
                return invocation.callRealMethod();
            }).when(readiness).fail(any(), any());
        }

        private Throwable activate() {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);
            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                return catchThrowable(() -> coordinator.activate(tracerProvider));
            }
        }

        private BootstrapFailure failure() {
            BootstrapFailure failure = coordinator.diagnosticSnapshot().failure();
            assertThat(failure).isNotNull();
            return failure;
        }

        @Override
        public void close() {
            closeQuietly(coordinator);
        }
    }

    private static final class TestRuntimeRegistration implements PlatformTracingMBeanRegistrar.RuntimeRegistration {

        private final UUID batchId = UUID.randomUUID();
        private final Throwable cleanupFailure;

        private TestRuntimeRegistration(Throwable cleanupFailure) {
            this.cleanupFailure = cleanupFailure;
        }

        @Override
        public UUID batchId() {
            return batchId;
        }

        @Override
        public void close() {
            if (cleanupFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cleanupFailure instanceof Error fatalFailure) {
                throw fatalFailure;
            }
        }
    }

    private static final class FailingOutputStream extends OutputStream {

        private final Error failure;
        private int writeAttempts;

        private FailingOutputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {
            failWrite();
        }

        @Override
        public void write(@Nonnull byte[] payload) {
            failWrite();
        }

        @Override
        public void write(@Nonnull byte[] payload, int offset, int length) {
            failWrite();
        }

        private void failWrite() {
            writeAttempts++;
            throw failure;
        }

        private int writeAttempts() {
            return writeAttempts;
        }
    }
}
