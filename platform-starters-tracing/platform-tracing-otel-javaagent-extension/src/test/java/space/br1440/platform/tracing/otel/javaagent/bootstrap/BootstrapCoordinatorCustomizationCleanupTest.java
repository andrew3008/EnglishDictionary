package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerFactory;
import javax.management.MBeanServerNotification;
import javax.management.NotificationListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrarTestSupport;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SAME_THREAD)
class BootstrapCoordinatorCustomizationCleanupTest {

    private static final String CUSTOMIZATION_FAILED = "SPAN_PROCESSOR_INITIALIZATION_FAILED";
    private static final long WAIT_SECONDS = 5L;

    @Test
    void customization_failure_releases_session_once_and_retains_diagnostics_until_close() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        IllegalStateException cause = new IllegalStateException("Customizer failed");
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            AutoconfigureSession session = coordinator.prepare(config());

            // when
            BootstrapException failure = catchBootstrapFailure(() -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                throw cause;
            }), CUSTOMIZATION_FAILED);

            // then
            assertThat(failure.getCause()).isSameAs(cause);
            verify(runtime).close();
            assertThat(coordinator.currentWire().evidence()).isEqualTo(session.evidence());
            assertThatDiagnosticMBean(server).isFailedWith(CUSTOMIZATION_FAILED);
            assertThatCode(coordinator::close).doesNotThrowAnyException();
            verify(runtime).close();
            assertThatDiagnosticMBean(server).isNotRegistered();
        }

        verify(runtime).close();
    }

    @Test
    void customization_cleanup_failure_preserves_primary_and_is_not_replayed_by_close() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        IllegalStateException cause = new IllegalStateException("Customizer failed");
        IllegalStateException cleanupFailure = new IllegalStateException("Scrubbing cleanup failed");
        doThrow(cleanupFailure).when(runtime).close();
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            AutoconfigureSession session = coordinator.prepare(config());

            // when
            BootstrapException failure = catchBootstrapFailure(() -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                throw cause;
            }), FAILED_ROLLBACK_INCOMPLETE);

            // then
            assertThat(failure.getCause()).isSameAs(cause);
            assertThat(failure.failure().primaryCode()).isEqualTo(CUSTOMIZATION_FAILED);
            assertThat(failure.failure().phase()).isEqualTo("customization");
            assertThat(BootstrapCleanupFailures.contains(cause, cleanupFailure)).isTrue();
            assertThat(coordinator.currentWire().evidence()).isEqualTo(session.evidence());
            assertThatDiagnosticMBean(server).isFailedWith(FAILED_ROLLBACK_INCOMPLETE);
            assertThatCode(coordinator::close).doesNotThrowAnyException();
            verify(runtime).close();
            assertThatDiagnosticMBean(server).isNotRegistered();
        }

        verify(runtime).close();
    }

    @Test
    void failed_readiness_publication_does_not_skip_customization_cleanup() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        PlatformExtensionReadiness readiness = spy(PlatformExtensionReadiness.forCurrentExtension());
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        IllegalStateException cause = new IllegalStateException("Customizer failed");
        IllegalStateException publicationFailure = new IllegalStateException("Failure publication failed");
        BootstrapCoordinator coordinator = coordinatorWithReadiness(server, readiness, runtime);

        try (coordinator) {
            coordinator.prepare(config());
            doThrow(publicationFailure).when(readiness).fail(anyString(), anyString());

            // when
            Throwable outcome = catchThrowable(() -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                throw cause;
            }));

            // then
            assertThat(outcome).isNotNull();
            assertThat(BootstrapCleanupFailures.contains(outcome, publicationFailure)).isTrue();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            verify(runtime).close();
            BootstrapException sticky = catchBootstrapFailure(
                    () -> coordinator.execute(CUSTOMIZATION_FAILED, () -> null), CUSTOMIZATION_FAILED
            );
            assertThat(sticky.getCause()).isSameAs(cause);
            assertThatCode(coordinator::close).doesNotThrowAnyException();
            verify(runtime).close();
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void fatal_customizer_failure_remains_primary_when_readiness_publication_fails() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        PlatformExtensionReadiness readiness = spy(PlatformExtensionReadiness.forCurrentExtension());
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        OutOfMemoryError cause = new OutOfMemoryError("Simulated customizer memory failure");
        IllegalStateException publicationFailure = new IllegalStateException("Failure publication failed");
        BootstrapCoordinator coordinator = coordinatorWithReadiness(server, readiness, runtime);

        try (coordinator) {
            coordinator.prepare(config());
            doThrow(publicationFailure).when(readiness).fail(anyString(), anyString());

            // when
            Throwable outcome = catchThrowable(() -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                throw cause;
            }));

            // then
            assertThat(outcome).isSameAs(cause);
            assertThat(BootstrapCleanupFailures.contains(cause, publicationFailure)).isTrue();
            verify(runtime).close();
            assertThatCode(coordinator::close).doesNotThrowAnyException();
            verify(runtime).close();
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    void publication_and_cleanup_failures_preserve_customization_primary_and_escalation() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        PlatformExtensionReadiness readiness = spy(PlatformExtensionReadiness.forCurrentExtension());
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        IllegalStateException cause = new IllegalStateException("Customizer failed");
        IllegalStateException publicationFailure = new IllegalStateException("Failure publication failed");
        IllegalStateException cleanupFailure = new IllegalStateException("Scrubbing cleanup failed");
        doThrow(cleanupFailure).when(runtime).close();
        BootstrapCoordinator coordinator = coordinatorWithReadiness(server, readiness, runtime);

        try (AgentLogCapture logs = AgentLogCapture.installed(); coordinator) {
            coordinator.prepare(config());
            doThrow(publicationFailure).when(readiness).fail(anyString(), anyString());

            // when
            Throwable outcome = catchThrowable(() -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                throw cause;
            }));

            // then
            assertThat(outcome).isNotNull();
            BootstrapException sticky = catchBootstrapFailure(
                    () -> coordinator.execute(CUSTOMIZATION_FAILED, () -> null), FAILED_ROLLBACK_INCOMPLETE
            );
            assertThat(sticky.getCause()).isSameAs(cause);
            assertThat(sticky.failure().primaryCode()).isEqualTo(CUSTOMIZATION_FAILED);
            assertThat(BootstrapCleanupFailures.contains(cause, publicationFailure)).isTrue();
            assertThat(BootstrapCleanupFailures.contains(cause, cleanupFailure)).isTrue();
            assertThat(logs.events())
                    .filteredOn(event -> "BOOTSTRAP_PHASE_FAILED".equals(event.eventName())
                            && FAILED_ROLLBACK_INCOMPLETE.equals(event.fields().get("failureCode")))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("phase", "customization"));
            verify(runtime).close();
            assertThatCode(coordinator::close).doesNotThrowAnyException();
            verify(runtime).close();
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void linkage_error_is_propagated_after_customization_cleanup() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        NoClassDefFoundError cause = new NoClassDefFoundError("Customizer linkage failed");

        try (BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build()) {
            coordinator.prepare(config());

            // when
            Throwable outcome = catchThrowable(() -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                throw cause;
            }));

            // then
            assertThat(outcome).isSameAs(cause);
            verify(runtime).close();
            assertThatDiagnosticMBean(server).isFailedWith(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
        }

        verify(runtime).close();
    }

    @Test
    void virtual_machine_error_remains_primary_after_failed_customization_cleanup() {
        // given
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        OutOfMemoryError cause = new OutOfMemoryError("Simulated customizer memory failure");
        IllegalStateException cleanupFailure = new IllegalStateException("Scrubbing cleanup failed");
        doThrow(cleanupFailure).when(runtime).close();

        try (BootstrapCoordinator coordinator = coordinator().scrubberFactory(factory(runtime)).build()) {
            coordinator.prepare(config());

            // when
            Throwable outcome = catchThrowable(() -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                throw cause;
            }));

            // then
            assertThat(outcome).isSameAs(cause);
            assertThat(BootstrapCleanupFailures.contains(cause, cleanupFailure)).isTrue();
            verify(runtime).close();
            assertThatCode(coordinator::close).doesNotThrowAnyException();
        }

        verify(runtime).close();
    }

    @Test
    void close_winning_customization_race_keeps_sole_session_ownership() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        CountDownLatch customizerEntered = new CountDownLatch(1);
        CountDownLatch failCustomizer = new CountDownLatch(1);
        IllegalStateException cause = new IllegalStateException("Customizer failed after close");
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        coordinator.prepare(config());
        FutureTask<Throwable> customization = new FutureTask<>(() -> catchThrowable(
                () -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                    customizerEntered.countDown();
                    await(failCustomizer);
                    throw cause;
                })
        ));

        try (coordinator) {
            // when
            Thread.ofPlatform().daemon().name("customizer-after-close-test").start(customization);
            assertThat(customizerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            coordinator.close();
            failCustomizer.countDown();
            Throwable outcome = customization.get(WAIT_SECONDS, TimeUnit.SECONDS);

            // then
            assertThat(outcome).isInstanceOf(BootstrapException.class);
            assertThat(outcome.getCause()).isSameAs(cause);
            assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSED);
            verify(runtime).close();
            assertThatDiagnosticMBean(server).isNotRegistered();
        } finally {
            failCustomizer.countDown();
        }
    }

    @Test
    void customization_winning_close_race_keeps_sole_session_ownership() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch diagnosticUnregistered = new CountDownLatch(1);
        NotificationListener unregistrationObserver = (notification, handback) -> {
            if (notification instanceof MBeanServerNotification event
                    && MBeanServerNotification.UNREGISTRATION_NOTIFICATION.equals(event.getType())
                    && DomainObjectNames.EXTENSION_READINESS.equals(event.getMBeanName())) {
                diagnosticUnregistered.countDown();
            }
        };
        doAnswer(invocation -> {
            cleanupEntered.countDown();
            await(releaseCleanup);
            return null;
        }).when(runtime).close();
        IllegalStateException cause = new IllegalStateException("Customizer failed before close");
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        coordinator.prepare(config());
        FutureTask<Throwable> customization = new FutureTask<>(() -> catchThrowable(
                () -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                    throw cause;
                })
        ));
        FutureTask<Throwable> close = new FutureTask<>(() -> catchThrowable(coordinator::close));
        server.addNotificationListener(MBeanServerDelegate.DELEGATE_NAME, unregistrationObserver, null, null);

        try (coordinator) {
            // when
            Thread.ofPlatform().daemon().name("customization-cleanup-owner-test").start(customization);
            assertThat(cleanupEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Thread.ofPlatform().daemon().name("customization-close-follower-test").start(close);
            assertThat(diagnosticUnregistered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            releaseCleanup.countDown();
            Throwable customizationOutcome = customization.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable closeOutcome = close.get(WAIT_SECONDS, TimeUnit.SECONDS);

            // then
            assertThat(customizationOutcome).isInstanceOf(BootstrapException.class);
            assertThat(customizationOutcome.getCause()).isSameAs(cause);
            assertThat(closeOutcome).isNull();
            verify(runtime, times(1)).close();
            assertThatDiagnosticMBean(server).isNotRegistered();
        } finally {
            releaseCleanup.countDown();
            server.removeNotificationListener(MBeanServerDelegate.DELEGATE_NAME, unregistrationObserver);
        }
    }

    private static PlatformScrubberFactory factory(ScrubbingRuntime runtime) {
        PlatformScrubberFactory factory = mock(PlatformScrubberFactory.class);
        //noinspection resource -- stubbing buildRuntime не открывает runtime; экземпляр закрывает AutoconfigureSession.close()
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test synchronization");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test synchronization", interrupted);
        }
    }
}
