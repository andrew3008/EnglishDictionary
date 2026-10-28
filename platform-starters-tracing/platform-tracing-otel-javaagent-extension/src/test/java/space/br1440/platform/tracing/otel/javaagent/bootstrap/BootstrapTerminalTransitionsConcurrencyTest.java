package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.SHUTDOWN_CLEANUP_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.CapturingShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SAME_THREAD)
class BootstrapTerminalTransitionsConcurrencyTest {

    private static final String BATCH_ID = "batch-1";
    private static final long WAIT_SECONDS = 5L;
    private static final long BLOCK_ASSERTION_MILLIS = 200L;

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void failed_is_visible_before_activation_rollback_starts() {
        // given
        TerminalFixture fixture = terminalFixture();
        CountDownLatch rollbackEntered = new CountDownLatch(1);
        CountDownLatch releaseRollback = new CountDownLatch(1);
        AtomicBoolean rollbackOutsideMonitor = new AtomicBoolean();
        AtomicReference<ExtensionReadinessWireSnapshot> rollbackSnapshot = new AtomicReference<>();
        try (BootstrapResources resources = new BootstrapResources()) {
            resources.push(() -> {
                rollbackOutsideMonitor.set(!Thread.holdsLock(fixture.lifecycle().monitor()));
                rollbackSnapshot.set(fixture.journal().current());
                rollbackEntered.countDown();
                awaitLatch(releaseRollback, "rollback release");
            }, "rollback-probe");
            IllegalStateException activationCause = new IllegalStateException("Activation failed");
            FutureTask<BootstrapException> activationTask = new FutureTask<>(() -> {
                BootstrapException classified = fixture.transitions().recordActivationFailure(
                        ACTIVATION_FAILED,
                        "readiness-publication",
                        activationCause,
                        BATCH_ID,
                        fixture.evidence()
                );
                return fixture.transitions().completeActivationFailure(
                        classified,
                        resources,
                        activationCause,
                        null,
                        BATCH_ID,
                        fixture.evidence()
                );
            });
            Thread activationThread = daemonThread("failed-before-rollback", activationTask);

            // when
            activationThread.start();
            try {
                awaitLatch(rollbackEntered, "activation rollback entry");

                // then
                assertThat(rollbackOutsideMonitor).isTrue();
                assertThat(fixture.lifecycle().state()).isEqualTo(BootstrapState.FAILED);
                assertThat(rollbackSnapshot.get().lifecycle()).isEqualTo(WIRE_FAILED);
                assertThat(rollbackSnapshot.get().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(rollbackSnapshot.get().failureCode()).isEqualTo(ACTIVATION_FAILED);

                releaseRollback.countDown();
                BootstrapException failure = awaitTask(activationTask, "activation rollback");
                assertThat(failure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(failure.failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(failure.getCause()).isSameAs(activationCause);
            } finally {
                releaseRollback.countDown();
                stopThreads(activationThread);
            }
        }
    }

    @Test
    void activation_failure_and_shutdown_hook_complete_without_monitor_resource_deadlock() {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        ControllableRuntimeRegistration runtimeRegistration = new ControllableRuntimeRegistration(() -> {
        });
        PlatformTracingMBeanRegistrar registrar = registrarFor(runtimeRegistration);
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        //noinspection resource -- stubbing buildRuntime не открывает runtime; экземпляр закрывает AutoconfigureSession.close()
        doReturn(scrubbingRuntime).when(scrubberFactory).buildRuntime(any());
        BootstrapCoordinator bootstrapCoordinator = coordinator()
                .hooks(hooks)
                .registrar(registrar)
                .scrubberFactory(scrubberFactory)
                .build();
        AutoconfigureSession session = bootstrapCoordinator.prepare(config());
        satisfyReadinessBarrier(session);

        CountDownLatch activationStepEntered = new CountDownLatch(1);
        CountDownLatch hookStarted = new CountDownLatch(1);
        CountDownLatch releaseActivationFailure = new CountDownLatch(1);
        AtomicInteger hookActionCalls = new AtomicInteger();
        IllegalStateException activationCause = new IllegalStateException("Activation step failed");
        doAnswer(ignored -> {
            activationStepEntered.countDown();
            awaitLatch(releaseActivationFailure, "activation failure release");
            throw activationCause;
        }).when(scrubbingRuntime).requireInstallationComplete();

        FutureTask<Void> hookTask = new FutureTask<>(() -> {
            hookStarted.countDown();
            hookActionCalls.incrementAndGet();
            hooks.runHook();
            return null;
        });
        Thread hookThread = daemonThread("hook-during-failed-activation", hookTask);

        // when
        try (SdkTracerProvider tracerProvider = providerFor(session)) {
            FutureTask<Throwable> activationTask = new FutureTask<>(
                    () -> catchThrowable(() -> bootstrapCoordinator.activate(tracerProvider))
            );
            Thread activationThread = daemonThread("failed-activation-owner", activationTask);
            try {
                activationThread.start();
                awaitLatch(activationStepEntered, "activation step entry");
                hookThread.start();
                awaitLatch(hookStarted, "shutdown hook action");
                releaseActivationFailure.countDown();

                Throwable activationThrown = awaitTask(activationTask, "failed activation");
                awaitTask(hookTask, "shutdown hook");

                // then
                assertThat(activationThrown).isInstanceOf(BootstrapException.class);
                BootstrapException activationFailure = (BootstrapException) activationThrown;
                assertThat(activationFailure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(activationFailure.failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(activationFailure.getCause()).isSameAs(activationCause);
                assertThat(bootstrapCoordinator.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(failureOf(registrar.extensionReadiness().snapshot()).primaryCode())
                        .isEqualTo(ACTIVATION_FAILED);
                assertThat(hooks.registrationCount()).isEqualTo(1);
                assertThat(hookActionCalls).hasValue(1);
                assertThat(runtimeRegistration.closeCalls()).isEqualTo(1);
                assertThat(catchThrowable(bootstrapCoordinator::close)).isNull();
                assertThat(runtimeRegistration.closeCalls()).isEqualTo(1);
            } finally {
                releaseActivationFailure.countDown();
                stopThreads(activationThread, hookThread);
            }
        } finally {
            releaseActivationFailure.countDown();
            closeQuietly(bootstrapCoordinator);
        }
    }

    @Test
    void section_one_publication_failure_still_rolls_back_resources_once() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(
                MBeanServerFactory.newMBeanServer(),
                agentInstanceId
        ));
        PlatformExtensionReadiness readiness = registrar.extensionReadiness();
        BootstrapReadinessJournal journal = spy(new BootstrapReadinessJournal(
                lifecycle.monitor(),
                readiness,
                agentInstanceId
        ));
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(lifecycle, journal);
        BootstrapActivation activation = new BootstrapActivation(lifecycle, journal, transitions);
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        ControllableRuntimeRegistration runtimeRegistration = new ControllableRuntimeRegistration(() -> {
        });
        stubRuntimeRegistration(registrar, runtimeRegistration);

        IllegalStateException activationCause = new IllegalStateException("Activation step failed");
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        doAnswer(ignored -> {
            throw activationCause;
        }).when(scrubbingRuntime).requireInstallationComplete();
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        //noinspection resource -- stubbing buildRuntime не открывает runtime; экземпляр закрывает AutoconfigureSession.close()
        doReturn(scrubbingRuntime).when(scrubberFactory).buildRuntime(any());
        ExtensionRuntimeEvidence evidence = RuntimeEvidenceTestFixtures.evidence(
                readiness.snapshot().extensionVersion()
        );
        AutoconfigureSessionFactory sessionFactory = new AutoconfigureSessionFactory(
                scrubberFactory,
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor()
        );
        lifecycle.beginPrepare();
        journal.publishInitializing();
        AutoconfigureSession session = sessionFactory.create(
                config(),
                registrar,
                agentInstanceId,
                evidence
        );
        lifecycle.completePrepare(session);

        UnsupportedOperationException sectionFailure =
                new UnsupportedOperationException("Failed readiness publication");
        AtomicBoolean publicationObservedFailed = new AtomicBoolean();
        AtomicInteger failurePublicationCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (failurePublicationCalls.getAndIncrement() == 0) {
                publicationObservedFailed.set(
                        lifecycle.state() == BootstrapState.FAILED
                                && lifecycle.failure().primaryCode().equals(ACTIVATION_FAILED)
                );
                throw sectionFailure;
            }
            return invocation.callRealMethod();
        }).when(journal).publishFailed(any(), any(), any());

        // when
        Throwable thrown;
        try (SdkTracerProvider tracerProvider = providerFor(session)) {
            thrown = catchThrowable(() -> activation.activate(
                    tracerProvider,
                    () -> hooks.register("test-bootstrap-shutdown", () -> {
                    })
            ));
        }

        // then
        assertThat(thrown).isSameAs(sectionFailure);
        assertThat(publicationObservedFailed).isTrue();
        assertThat(failurePublicationCalls).hasValue(1);
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(lifecycle.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(lifecycle.failure().cause()).isSameAs(activationCause);
        assertThat(hooks.registrationCount()).isEqualTo(1);
        assertThat(runtimeRegistration.closeCalls()).isEqualTo(1);
    }

    @Test
    void activation_rollback_while_holding_lifecycle_monitor_fails_before_resource_close() {
        // given
        TerminalFixture fixture = terminalFixture();
        AtomicInteger closeCalls = new AtomicInteger();
        BootstrapResources resources = new BootstrapResources();
        resources.push(closeCalls::incrementAndGet, "close-probe");
        IllegalStateException activationCause = new IllegalStateException("Activation failed");
        BootstrapException classified = fixture.transitions().recordActivationFailure(
                ACTIVATION_FAILED,
                "readiness-publication",
                activationCause,
                BATCH_ID,
                fixture.evidence()
        );

        // when
        Throwable monitorViolation;
        synchronized (fixture.lifecycle().monitor()) {
            monitorViolation = catchThrowable(() -> {
                throw fixture.transitions().completeActivationFailure(
                        classified,
                        resources,
                        activationCause,
                        null,
                        BATCH_ID,
                        fixture.evidence()
                );
            });
        }

        // then
        assertThat(monitorViolation)
                .isInstanceOf(IllegalMonitorStateException.class)
                .hasMessage("Activation rollback must run outside the bootstrap lifecycle monitor");
        assertThat(closeCalls).hasValue(0);
        resources.close();
        assertThat(closeCalls).hasValue(1);
    }

    @Test
    void close_during_activation_rollback_reuses_failure_without_duplicate_publication_or_log() {
        // given
        AtomicReference<BootstrapCoordinator> coordinatorReference = new AtomicReference<>();
        AtomicReference<FutureTask<Throwable>> closeTaskReference = new AtomicReference<>();
        AtomicReference<Thread> closeThreadReference = new AtomicReference<>();
        CountDownLatch closeStarted = new CountDownLatch(1);
        IllegalStateException cleanupCause = new IllegalStateException("Runtime cleanup failed");
        ControllableRuntimeRegistration runtimeRegistration = new ControllableRuntimeRegistration(() -> {
            FutureTask<Throwable> closeTask = new FutureTask<>(() -> {
                closeStarted.countDown();
                return closeCapturing(coordinatorReference.get());
            });
            if (!closeTaskReference.compareAndSet(null, closeTask)) {
                throw new IllegalStateException("Concurrent close task is already installed");
            }
            Thread closeThread = daemonThread("close-during-activation-rollback", closeTask);
            closeThreadReference.set(closeThread);
            closeThread.start();
            awaitLatch(closeStarted, "concurrent close entry");
            assertTaskStillRunning(closeTask, "concurrent close during activation rollback");
            throw cleanupCause;
        });
        PlatformTracingMBeanRegistrar registrar = registrarFor(runtimeRegistration);
        PlatformExtensionReadiness readiness = spy(registrar.extensionReadiness());
        doReturn(readiness).when(registrar).extensionReadiness();
        AtomicInteger failurePublicationCalls = new AtomicInteger();
        doAnswer(invocation -> {
            failurePublicationCalls.incrementAndGet();
            return invocation.callRealMethod();
        }).when(readiness).fail(any(), any());

        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        IllegalStateException activationCause = new IllegalStateException("Activation step failed");
        doAnswer(ignored -> {
            throw activationCause;
        }).when(scrubbingRuntime).requireInstallationComplete();
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        //noinspection resource -- stubbing buildRuntime не открывает runtime; экземпляр закрывает AutoconfigureSession.close()
        doReturn(scrubbingRuntime).when(scrubberFactory).buildRuntime(any());
        BootstrapCoordinator bootstrapCoordinator = coordinator()
                .registrar(registrar)
                .scrubberFactory(scrubberFactory)
                .build();
        coordinatorReference.set(bootstrapCoordinator);
        AutoconfigureSession session = bootstrapCoordinator.prepare(config());
        satisfyReadinessBarrier(session);

        // when
        try {
            Throwable activationThrown;
            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                activationThrown = catchThrowable(() -> bootstrapCoordinator.activate(tracerProvider));
            }
            FutureTask<Throwable> closeTask = closeTaskReference.get();
            Throwable closeThrown = awaitTask(closeTask, "close during activation rollback");
            Throwable repeatedCloseThrown = catchThrowable(bootstrapCoordinator::close);

            // then
            assertThat(activationThrown).isInstanceOf(BootstrapException.class);
            BootstrapException activationFailure = (BootstrapException) activationThrown;
            assertThat(activationFailure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
            assertThat(activationFailure.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            assertThat(activationFailure.getCause()).isSameAs(activationCause);
            assertThat(activationCause.getSuppressed()).singleElement().isSameAs(closeThrown);
            assertThat(closeThrown).hasRootCause(cleanupCause);
            assertThat(repeatedCloseThrown).isSameAs(closeThrown);
            assertThat(runtimeRegistration.closeCalls()).isEqualTo(1);
            assertThat(failurePublicationCalls).hasValue(2);
            assertThat(bootstrapCoordinator.currentWire().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
            assertThat(bootstrapCoordinator.currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            assertThat(phaseFailureEvents())
                    .extracting(event -> event.fields().get("failureCode"))
                    .containsExactly(ACTIVATION_FAILED, FAILED_ROLLBACK_INCOMPLETE);
        } finally {
            Thread closeThread = closeThreadReference.get();
            if (closeThread != null) {
                stopThreads(closeThread);
            }
            closeQuietly(bootstrapCoordinator);
        }
    }

    @Test
    void direct_owner_and_hook_follower_share_one_failed_close_flight() {
        // given
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        IllegalStateException cleanupCause = new IllegalStateException("Runtime cleanup failed");
        ControllableRuntimeRegistration runtimeRegistration = new ControllableRuntimeRegistration(() -> {
            cleanupEntered.countDown();
            awaitLatch(releaseCleanup, "runtime cleanup release");
            throw cleanupCause;
        });
        ActivatedFixture fixture = activatedFixture(runtimeRegistration);
        FutureTask<Throwable> ownerTask = new FutureTask<>(() -> catchThrowable(fixture::close));
        Thread ownerThread = daemonThread("direct-close-owner", ownerTask);
        CountDownLatch hookStarted = new CountDownLatch(1);
        FutureTask<Void> hookTask = new FutureTask<>(() -> {
            hookStarted.countDown();
            fixture.hooks().runHook();
            return null;
        });
        Thread hookThread = daemonThread("hook-close-follower", hookTask);

        // when
        try {
            ownerThread.start();
            awaitLatch(cleanupEntered, "owner cleanup entry");
            hookThread.start();
            awaitLatch(hookStarted, "hook follower entry");
            assertTaskStillRunning(hookTask, "hook close follower");
            releaseCleanup.countDown();

            Throwable ownerFailure = awaitTask(ownerTask, "direct close owner");
            awaitTask(hookTask, "hook close follower");
            Throwable repeatedFailure = catchThrowable(fixture::close);

            // then
            assertThat(ownerFailure).hasRootCause(cleanupCause);
            assertThat(repeatedFailure).isSameAs(ownerFailure);
            assertThat(runtimeRegistration.closeCalls()).isEqualTo(1);
            assertThat(fixture.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(fixture.currentWire().failureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
            assertThat(closeFailureEvents()).hasSize(1);
        } finally {
            releaseCleanup.countDown();
            stopThreads(ownerThread, hookThread);
            closeQuietly(fixture);
        }
    }

    @Test
    void hook_owner_and_direct_follower_share_one_failed_close_flight() {
        // given
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        IllegalStateException cleanupCause = new IllegalStateException("Runtime cleanup failed");
        ControllableRuntimeRegistration runtimeRegistration = new ControllableRuntimeRegistration(() -> {
            cleanupEntered.countDown();
            awaitLatch(releaseCleanup, "runtime cleanup release");
            throw cleanupCause;
        });
        ActivatedFixture fixture = activatedFixture(runtimeRegistration);
        FutureTask<Void> hookTask = new FutureTask<>(() -> {
            fixture.hooks().runHook();
            return null;
        });
        Thread hookThread = daemonThread("hook-close-owner", hookTask);
        CountDownLatch directStarted = new CountDownLatch(1);
        FutureTask<Throwable> directTask = new FutureTask<>(() -> {
            directStarted.countDown();
            return catchThrowable(fixture::close);
        });
        Thread directThread = daemonThread("direct-close-follower", directTask);

        // when
        try {
            hookThread.start();
            awaitLatch(cleanupEntered, "hook owner cleanup entry");
            directThread.start();
            awaitLatch(directStarted, "direct follower entry");
            assertTaskStillRunning(directTask, "direct close follower");
            releaseCleanup.countDown();

            awaitTask(hookTask, "hook close owner");
            Throwable directFailure = awaitTask(directTask, "direct close follower");
            Throwable repeatedFailure = catchThrowable(fixture::close);

            // then
            assertThat(directFailure).hasRootCause(cleanupCause);
            assertThat(repeatedFailure).isSameAs(directFailure);
            assertThat(runtimeRegistration.closeCalls()).isEqualTo(1);
            assertThat(closeFailureEvents()).hasSize(1);
        } finally {
            releaseCleanup.countDown();
            stopThreads(hookThread, directThread);
            closeQuietly(fixture);
        }
    }

    @Test
    void direct_callers_share_the_exact_failed_close_outcome() {
        // given
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        IllegalStateException cleanupCause = new IllegalStateException("Runtime cleanup failed");
        ControllableRuntimeRegistration runtimeRegistration = new ControllableRuntimeRegistration(() -> {
            cleanupEntered.countDown();
            awaitLatch(releaseCleanup, "runtime cleanup release");
            throw cleanupCause;
        });
        ActivatedFixture fixture = activatedFixture(runtimeRegistration);
        FutureTask<Throwable> ownerTask = new FutureTask<>(() -> catchThrowable(fixture::close));
        Thread ownerThread = daemonThread("first-direct-close", ownerTask);
        CountDownLatch followerStarted = new CountDownLatch(1);
        FutureTask<Throwable> followerTask = new FutureTask<>(() -> {
            followerStarted.countDown();
            return catchThrowable(fixture::close);
        });
        Thread followerThread = daemonThread("second-direct-close", followerTask);

        // when
        try {
            ownerThread.start();
            awaitLatch(cleanupEntered, "first direct cleanup entry");
            followerThread.start();
            awaitLatch(followerStarted, "second direct close entry");
            assertTaskStillRunning(followerTask, "second direct close");
            releaseCleanup.countDown();

            Throwable ownerFailure = awaitTask(ownerTask, "first direct close");
            Throwable followerFailure = awaitTask(followerTask, "second direct close");

            // then
            assertThat(ownerFailure).hasRootCause(cleanupCause);
            assertThat(followerFailure).isSameAs(ownerFailure);
            assertThat(runtimeRegistration.closeCalls()).isEqualTo(1);
            assertThat(closeFailureEvents()).hasSize(1);
        } finally {
            releaseCleanup.countDown();
            stopThreads(ownerThread, followerThread);
            closeQuietly(fixture);
        }
    }

    @Test
    void interrupted_follower_waits_for_outcome_and_restores_interrupt_status() {
        // given
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        IllegalStateException cleanupCause = new IllegalStateException("Runtime cleanup failed");
        ControllableRuntimeRegistration runtimeRegistration = new ControllableRuntimeRegistration(() -> {
            cleanupEntered.countDown();
            awaitLatch(releaseCleanup, "runtime cleanup release");
            throw cleanupCause;
        });
        ActivatedFixture fixture = activatedFixture(runtimeRegistration);
        FutureTask<Throwable> ownerTask = new FutureTask<>(() -> catchThrowable(fixture::close));
        Thread ownerThread = daemonThread("interrupted-follower-owner", ownerTask);
        CountDownLatch followerStarted = new CountDownLatch(1);
        FutureTask<InterruptedCloseOutcome> followerTask = new FutureTask<>(() -> {
            followerStarted.countDown();
            Throwable failure = catchThrowable(fixture::close);
            return new InterruptedCloseOutcome(failure, Thread.currentThread().isInterrupted());
        });
        Thread followerThread = daemonThread("interrupted-close-follower", followerTask);

        // when
        try {
            ownerThread.start();
            awaitLatch(cleanupEntered, "interrupted follower owner cleanup entry");
            followerThread.start();
            awaitLatch(followerStarted, "interrupted follower entry");
            followerThread.interrupt();
            assertTaskStillRunning(followerTask, "interrupted close follower");
            releaseCleanup.countDown();

            Throwable ownerFailure = awaitTask(ownerTask, "interrupted follower owner");
            InterruptedCloseOutcome follower = awaitTask(followerTask, "interrupted close follower");

            // then
            assertThat(follower.failure()).isSameAs(ownerFailure);
            assertThat(follower.interrupted()).isTrue();
            assertThat(runtimeRegistration.closeCalls()).isEqualTo(1);
            assertThat(closeFailureEvents()).hasSize(1);
        } finally {
            releaseCleanup.countDown();
            stopThreads(ownerThread, followerThread);
            closeQuietly(fixture);
        }
    }

    @Test
    void reentrant_owner_close_returns_and_success_is_replayed() {
        // given
        AtomicReference<ActivatedFixture> fixtureReference = new AtomicReference<>();
        AtomicInteger reentrantReturns = new AtomicInteger();
        ControllableRuntimeRegistration runtimeRegistration = new ControllableRuntimeRegistration(() -> {
            fixtureReference.get().close();
            reentrantReturns.incrementAndGet();
        });
        ActivatedFixture fixture = activatedFixture(runtimeRegistration);
        fixtureReference.set(fixture);
        FutureTask<Throwable> ownerTask = new FutureTask<>(() -> catchThrowable(fixture::close));
        Thread ownerThread = daemonThread("reentrant-close-owner", ownerTask);

        // when
        try {
            ownerThread.start();
            Throwable ownerFailure = awaitTask(ownerTask, "reentrant close owner");
            Throwable repeatedFailure = catchThrowable(fixture::close);

            // then
            assertThat(ownerFailure).isNull();
            assertThat(repeatedFailure).isNull();
            assertThat(reentrantReturns).hasValue(1);
            assertThat(runtimeRegistration.closeCalls()).isEqualTo(1);
            assertThat(fixture.state()).isEqualTo(BootstrapState.CLOSED);
            assertThat(closeFailureEvents()).isEmpty();
        } finally {
            stopThreads(ownerThread);
            closeQuietly(fixture);
        }
    }

    private List<CapturedLogEvent> phaseFailureEvents() {
        return logs.events().stream()
                .filter(event -> event.eventCode() == AgentEventCode.BOOT_CLASSIFIED_FAILURE)
                .filter(event -> "Bootstrap phase failed".equals(event.message()))
                .toList();
    }

    private List<CapturedLogEvent> closeFailureEvents() {
        return phaseFailureEvents().stream()
                .filter(event -> "close".equals(event.fields().get("phase")))
                .toList();
    }

    private static ActivatedFixture activatedFixture(ControllableRuntimeRegistration runtimeRegistration) {
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        PlatformTracingMBeanRegistrar registrar = registrarFor(runtimeRegistration);
        BootstrapCoordinator bootstrapCoordinator = coordinator()
                .hooks(hooks)
                .registrar(registrar)
                .build();
        AutoconfigureSession session = bootstrapCoordinator.prepare(config());
        satisfyReadinessBarrier(session);
        try (SdkTracerProvider tracerProvider = providerFor(session)) {
            bootstrapCoordinator.activate(tracerProvider);
        }
        return new ActivatedFixture(bootstrapCoordinator, hooks);
    }

    private static PlatformTracingMBeanRegistrar registrarFor(
            PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration
    ) {
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(
                MBeanServerFactory.newMBeanServer(),
                AgentInstanceId.random()
        ));
        stubRuntimeRegistration(registrar, runtimeRegistration);
        return registrar;
    }

    private static void stubRuntimeRegistration(PlatformTracingMBeanRegistrar registrar,
                                                PlatformTracingMBeanRegistrar.RuntimeRegistration registration) {
        //noinspection resource — регистрацию закрывает координатор
        doReturn(registration).when(registrar).registerRuntimeControls(any());
    }

    private static TerminalFixture terminalFixture() {
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        PlatformExtensionReadiness readiness = readyReadiness();
        ExtensionRuntimeEvidence evidence = ExtensionRuntimeEvidence.unavailable(
                readiness.snapshot().extensionVersion()
        );
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(
                lifecycle.monitor(),
                readiness,
                AgentInstanceId.random()
        );
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(lifecycle, journal);
        lifecycle.commitActive();
        return new TerminalFixture(lifecycle, journal, transitions, evidence);
    }

    private static PlatformExtensionReadiness readyReadiness() {
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }
        readiness.completeInitialization();
        return readiness;
    }

    private static Thread daemonThread(String name, FutureTask<?> task) {
        return Thread.ofPlatform()
                .daemon()
                .name(name)
                .unstarted(task);
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError(description + " did not occur within " + WAIT_SECONDS + " seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description + " was interrupted", interrupted);
        }
    }

    private static void assertTaskStillRunning(FutureTask<?> task, String description) {
        try {
            task.get(BLOCK_ASSERTION_MILLIS, TimeUnit.MILLISECONDS);
            throw new AssertionError(description + " completed before the close owner settled the outcome");
        } catch (TimeoutException expected) {
            assertThat(task.isDone()).isFalse();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description + " wait was interrupted", interrupted);
        } catch (ExecutionException executionFailure) {
            throw new AssertionError(description + " failed", executionFailure.getCause());
        }
    }

    private static void awaitThreadTermination(Thread thread, String description) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description + " wait was interrupted", interrupted);
        }
        if (thread.isAlive()) {
            throw new AssertionError(description + " did not terminate within " + WAIT_SECONDS + " seconds");
        }
    }

    private static Throwable closeCapturing(BootstrapCoordinator coordinator) {
        return catchThrowable(coordinator::close);
    }

    private static void closeQuietly(BootstrapCoordinator coordinator) {
        try {
            coordinator.close();
        } catch (Throwable ignored) {
            // повторное закрытие в finally не должно подменять отказ теста
        }
    }

    private static void closeQuietly(ActivatedFixture fixture) {
        closeQuietly(fixture.coordinator);
    }

    private static void stopThreads(Thread... threads) {
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            awaitThreadTermination(thread, thread.getName());
        }
    }

    private static <T> T awaitTask(FutureTask<T> task, String description) {
        try {
            return task.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description + " wait was interrupted", interrupted);
        } catch (ExecutionException executionFailure) {
            throw new AssertionError(description + " failed", executionFailure.getCause());
        } catch (TimeoutException timeout) {
            throw new AssertionError(description + " did not finish within " + WAIT_SECONDS + " seconds", timeout);
        }
    }

    private static final class ControllableRuntimeRegistration implements PlatformTracingMBeanRegistrar.RuntimeRegistration {

        private final UUID batchId = UUID.randomUUID();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final Runnable closeAction;

        private ControllableRuntimeRegistration(Runnable closeAction) {
            this.closeAction = closeAction;
        }

        @Override
        public UUID batchId() {
            return batchId;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeAction.run();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private record ActivatedFixture(BootstrapCoordinator coordinator, CapturingShutdownHookRegistry hooks) {

        private BootstrapState state() {
                return coordinator.state();
            }

            private ExtensionReadinessWireSnapshot currentWire() {
                return coordinator.currentWire();
            }

            private void close() {
                coordinator.close();
            }
        }

    private record InterruptedCloseOutcome(Throwable failure, boolean interrupted) {
    }

    private record TerminalFixture(BootstrapLifecycleState lifecycle,
                                   BootstrapReadinessJournal journal,
                                   BootstrapTerminalTransitions transitions,
                                   ExtensionRuntimeEvidence evidence) {
    }
}
