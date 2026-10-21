package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_READY;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.CapturingShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.Registration;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SAME_THREAD)
class BootstrapTerminalTransitionsConcurrencyTest {

    private static final String BATCH_ID = "batch-1";
    private static final long WAIT_SECONDS = 5L;
    private static final long BLOCK_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);

    @Test
    void failed_is_visible_before_activation_rollback_starts() {
        // given
        TerminalFixture fixture = terminalFixture();
        CountDownLatch rollbackEntered = new CountDownLatch(1);
        CountDownLatch releaseRollback = new CountDownLatch(1);
        AtomicBoolean rollbackOutsideMonitor = new AtomicBoolean();
        AtomicReference<ExtensionReadinessWireSnapshot> rollbackSnapshot = new AtomicReference<>();
        BootstrapResources resources = new BootstrapResources(noOpHooks());
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
        awaitLatch(rollbackEntered, "activation rollback entry");

        // then
        try {
            assertThat(rollbackOutsideMonitor).isTrue();
            assertThat(fixture.lifecycle().state()).isEqualTo(BootstrapState.FAILED);
            assertThat(rollbackSnapshot.get().lifecycle()).isEqualTo(WIRE_FAILED);
            assertThat(rollbackSnapshot.get().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
            assertThat(rollbackSnapshot.get().failureCode()).isEqualTo(ACTIVATION_FAILED);
        } finally {
            releaseRollback.countDown();
        }

        BootstrapException failure = awaitTask(activationTask, "activation rollback");
        assertThat(failure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failure.failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failure.getCause()).isSameAs(activationCause);
    }

    @Test
    void activation_failure_and_shutdown_hook_complete_without_monitor_resource_deadlock() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(
                MBeanServerFactory.newMBeanServer(),
                agentInstanceId
        ));
        PlatformExtensionReadiness readiness = registrar.extensionReadiness();
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(
                lifecycle.monitor(),
                readiness,
                agentInstanceId
        );
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(
                lifecycle,
                journal,
                new DiagnosticReadinessHandle()
        );

        CapturingShutdownHookRegistry capturedHooks = new CapturingShutdownHookRegistry();
        AtomicInteger hookRegistrationCloseCalls = new AtomicInteger();
        ShutdownHookRegistry hooks = (threadName, action) -> {
            Registration registered = capturedHooks.register(threadName, action);
            return () -> {
                hookRegistrationCloseCalls.incrementAndGet();
                registered.close();
            };
        };
        BootstrapActivation activation = new BootstrapActivation(lifecycle, journal, transitions, hooks);

        AtomicInteger runtimeRegistrationCloseCalls = new AtomicInteger();
        UUID batchId = UUID.randomUUID();
        PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration =
                new PlatformTracingMBeanRegistrar.RuntimeRegistration() {
                    @Override
                    public UUID batchId() {
                        return batchId;
                    }

                    @Override
                    public void close() {
                        runtimeRegistrationCloseCalls.incrementAndGet();
                    }
                };
        doReturn(runtimeRegistration).when(registrar).registerRuntimeControls(any());

        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
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

        CountDownLatch activationStepEntered = new CountDownLatch(1);
        AtomicBoolean activationStepHoldsMonitor = new AtomicBoolean();
        AtomicReference<ThreadInfo> blockedHook = new AtomicReference<>();
        IllegalStateException activationCause = new IllegalStateException("Activation step failed");
        FutureTask<Void> hookTask = new FutureTask<>(() -> {
            capturedHooks.runHook();
            return null;
        });
        Thread hookThread = daemonThread("hook-blocked-on-lifecycle-monitor", hookTask);

        doAnswer(ignored -> {
            boolean holdsMonitor = Thread.holdsLock(lifecycle.monitor());
            activationStepHoldsMonitor.set(holdsMonitor);
            activationStepEntered.countDown();
            if (!holdsMonitor) {
                throw new AssertionError("Activation step does not hold the lifecycle monitor");
            }

            blockedHook.set(awaitBlocked(
                    hookThread,
                    Thread.currentThread(),
                    lifecycle.monitor(),
                    "shutdown hook on the lifecycle monitor"
            ));
            throw activationCause;
        }).when(scrubbingRuntime).requireInstallationComplete();

        try (SdkTracerProvider tracerProvider = providerFor(session)) {
            FutureTask<Throwable> activationTask = new FutureTask<>(
                    () -> catchThrowable(() -> activation.activate(tracerProvider))
            );
            Thread activationThread = daemonThread("activation-owning-lifecycle-monitor", activationTask);

            // when
            activationThread.start();
            try {
                awaitLatch(activationStepEntered, "activation step entry");
                hookThread.start();
                Throwable activationThrown = awaitTask(activationTask, "failed activation");
                awaitTask(hookTask, "shutdown hook");
                awaitThreadTermination(activationThread, "activation thread");
                awaitThreadTermination(hookThread, "shutdown hook thread");

                // then
                assertThat(activationStepHoldsMonitor).isTrue();
                assertThat(blockedHook.get().getThreadState()).isEqualTo(Thread.State.BLOCKED);
                assertThat(blockedHook.get().getLockOwnerId()).isEqualTo(activationThread.threadId());
                assertThat(blockedHook.get().getLockInfo().getClassName())
                        .isEqualTo(lifecycle.monitor().getClass().getName());
                assertThat(blockedHook.get().getLockInfo().getIdentityHashCode())
                        .isEqualTo(System.identityHashCode(lifecycle.monitor()));
                assertThat(activationThrown).isInstanceOf(BootstrapException.class);
                BootstrapException activationFailure = (BootstrapException) activationThrown;
                assertThat(activationFailure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(activationFailure.failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(activationFailure.getCause()).isSameAs(activationCause);
                assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(lifecycle.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(lifecycle.failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(journal.current().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(journal.current().failureCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(failureOf(readiness.snapshot()).primaryCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(failureOf(readiness.snapshot()).effectiveCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(hookRegistrationCloseCalls).hasValue(1);
                assertThat(runtimeRegistrationCloseCalls).hasValue(1);
                assertThat(activationTask.isDone()).isTrue();
                assertThat(hookTask.isDone()).isTrue();
                assertThat(activationThread.isAlive()).isFalse();
                assertThat(hookThread.isAlive()).isFalse();
                assertParticipantsNotDeadlocked(activationThread, hookThread);
            } finally {
                activationThread.interrupt();
                hookThread.interrupt();
            }
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
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(
                lifecycle,
                journal,
                new DiagnosticReadinessHandle()
        );

        CapturingShutdownHookRegistry capturedHooks = new CapturingShutdownHookRegistry();
        AtomicInteger hookRegistrationCloseCalls = new AtomicInteger();
        ShutdownHookRegistry hooks = (threadName, action) -> {
            Registration registered = capturedHooks.register(threadName, action);
            return () -> {
                hookRegistrationCloseCalls.incrementAndGet();
                registered.close();
            };
        };
        BootstrapActivation activation = new BootstrapActivation(lifecycle, journal, transitions, hooks);

        AtomicInteger runtimeRegistrationCloseCalls = new AtomicInteger();
        UUID batchId = UUID.randomUUID();
        PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration =
                new PlatformTracingMBeanRegistrar.RuntimeRegistration() {
                    @Override
                    public UUID batchId() {
                        return batchId;
                    }

                    @Override
                    public void close() {
                        runtimeRegistrationCloseCalls.incrementAndGet();
                    }
                };
        doReturn(runtimeRegistration).when(registrar).registerRuntimeControls(any());

        IllegalStateException activationCause = new IllegalStateException("Activation step failed");
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        doAnswer(ignored -> {
            throw activationCause;
        }).when(scrubbingRuntime).requireInstallationComplete();
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
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
            thrown = catchThrowable(() -> activation.activate(tracerProvider));
        }

        // then
        assertThat(thrown).isSameAs(sectionFailure);
        assertThat(publicationObservedFailed).isTrue();
        assertThat(failurePublicationCalls).hasValue(1);
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(lifecycle.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(lifecycle.failure().cause()).isSameAs(activationCause);
        assertThat(hookRegistrationCloseCalls).hasValue(1);
        assertThat(runtimeRegistrationCloseCalls).hasValue(1);
    }

    @Test
    void close_during_activation_rollback_observes_same_cleanup_failure() {
        // given
        AtomicReference<BootstrapCoordinator> coordinatorRef = new AtomicReference<>();
        AtomicReference<ThreadInfo> blockedClose = new AtomicReference<>();
        AtomicInteger cleanupCalls = new AtomicInteger();
        IllegalStateException cleanupCause = new IllegalStateException("Runtime cleanup failed");
        FutureTask<Throwable> closeTask = new FutureTask<>(
                () -> catchThrowable(() -> coordinatorRef.get().close())
        );
        Thread closeThread = daemonThread("close-during-rollback", closeTask);
        ShutdownHookRegistry hooks = (threadName, action) -> () -> {
            cleanupCalls.incrementAndGet();
            closeThread.start();
            blockedClose.set(awaitBlocked(
                    closeThread,
                    Thread.currentThread(),
                    "coordinator close during activation rollback"
            ));
            throw cleanupCause;
        };

        try (BootstrapCoordinator coordinator = coordinator().hooks(hooks).build()) {
            coordinatorRef.set(coordinator);
            AutoconfigureSession session = coordinator.prepare(config());
            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                FutureTask<Throwable> activationTask = new FutureTask<>(
                        () -> catchThrowable(() -> coordinator.activate(tracerProvider))
                );
                Thread activationThread = daemonThread("activation-with-concurrent-close", activationTask);

                // when
                activationThread.start();
                Throwable activationThrown = awaitTask(activationTask, "failed activation");
                Throwable closeThrown = awaitTask(closeTask, "concurrent coordinator close");

                // then
                assertThat(blockedClose.get().getThreadState()).isEqualTo(Thread.State.BLOCKED);
                assertThat(blockedClose.get().getLockOwnerId()).isEqualTo(activationThread.threadId());
                assertThat(cleanupCalls).hasValue(1);
                assertThat(activationThrown).isInstanceOf(BootstrapException.class);
                BootstrapException activationFailure = (BootstrapException) activationThrown;
                assertThat(activationFailure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(activationFailure.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
                assertThat(activationFailure.getCause().getSuppressed()).singleElement()
                        .isSameAs(closeThrown);
                assertThat(closeThrown).hasRootCause(cleanupCause);
                assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(coordinator.currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            }
        }
    }

    @Test
    void shutdown_hook_and_close_share_fatal_diagnostic_failure_without_stale_transition() throws Exception {
        // given
        AtomicReference<Runnable> hookAction = new AtomicReference<>();
        CountDownLatch hookRemovalEntered = new CountDownLatch(1);
        CountDownLatch releaseHookRemoval = new CountDownLatch(1);
        ShutdownHookRegistry hooks = (threadName, action) -> {
            if (!hookAction.compareAndSet(null, action)) {
                throw new IllegalStateException("Shutdown hook is already registered");
            }

            return () -> {
                hookRemovalEntered.countDown();
                awaitLatch(releaseHookRemoval, "shutdown hook removal release");
            };
        };
        DiagnosticReadinessHandle diagnosticHandle = new DiagnosticReadinessHandle();
        CountDownLatch diagnosticCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseDiagnosticClose = new CountDownLatch(1);
        AtomicReference<Thread> diagnosticCloseOwner = new AtomicReference<>();
        NoClassDefFoundError diagnosticFailure =
                new NoClassDefFoundError("Fatal diagnostic readiness cleanup failure");

        try (BootstrapCoordinator coordinator = coordinator()
                .hooks(hooks)
                .diagnosticHandle(diagnosticHandle)
                .build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);
            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                coordinator.activate(tracerProvider);
            }

            Runnable registeredHook = hookAction.get();
            assertThat(registeredHook).isNotNull();
            FutureTask<Void> hookTask = new FutureTask<>(() -> {
                registeredHook.run();
                return null;
            });
            Thread hookThread = daemonThread("shutdown-hook-owning-resources", hookTask);
            FutureTask<Throwable> closeTask = new FutureTask<>(() -> catchThrowable(coordinator::close));
            Thread closeThread = daemonThread("close-racing-with-shutdown-hook", closeTask);
            try (AutoCloseable previousRegistration = diagnosticHandle.replace(() -> {
                diagnosticCloseOwner.set(Thread.currentThread());
                diagnosticCloseEntered.countDown();
                awaitLatch(releaseDiagnosticClose, "diagnostic close release");
                throw diagnosticFailure;
            })) {
                // when
                hookThread.start();
                awaitLatch(hookRemovalEntered, "shutdown hook removal entry");
                closeThread.start();
                ThreadInfo blockedOnResources = awaitBlocked(
                        closeThread,
                        hookThread,
                        "coordinator close on activation resources"
                );

                // then
                try {
                    assertThat(blockedOnResources.getThreadState()).isEqualTo(Thread.State.BLOCKED);
                    assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSING);
                    assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_READY);
                    assertThat(coordinator.currentWire().primaryFailureCode()).isEmpty();
                    assertThat(coordinator.currentWire().failureCode()).isEmpty();
                } finally {
                    releaseHookRemoval.countDown();
                }

                awaitLatch(diagnosticCloseEntered, "diagnostic close entry");
                Thread diagnosticOwner = diagnosticCloseOwner.get();
                assertThat(diagnosticOwner).isIn(hookThread, closeThread);
                Thread blockedThread = diagnosticOwner == hookThread ? closeThread : hookThread;
                ThreadInfo blockedOnDiagnostic = awaitBlocked(
                        blockedThread,
                        diagnosticOwner,
                        diagnosticHandle,
                        "competing close on diagnostic readiness handle"
                );
                try {
                    assertThat(blockedOnDiagnostic.getLockInfo().getClassName())
                            .isEqualTo(DiagnosticReadinessHandle.class.getName());
                    assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSING);
                    assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_READY);
                } finally {
                    releaseDiagnosticClose.countDown();
                }

                awaitTask(hookTask, "shutdown hook");
                Throwable closeThrown = awaitTask(closeTask, "coordinator close racing with shutdown hook");

                assertThat(closeThrown).isSameAs(diagnosticFailure);
                assertThat(diagnosticFailure.getSuppressed()).isEmpty();
                assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_FAILED);
                assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
                assertThat(coordinator.currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
                assertParticipantsNotDeadlocked(hookThread, closeThread);
            } finally {
                releaseHookRemoval.countDown();
                releaseDiagnosticClose.countDown();
                hookThread.interrupt();
                closeThread.interrupt();
                awaitThreadTermination(hookThread, "shutdown hook thread");
                awaitThreadTermination(closeThread, "coordinator close thread");
            }
        }
    }

    @Test
    void activation_rollback_while_holding_lifecycle_monitor_fails_before_resource_close() {
        // given
        TerminalFixture fixture = terminalFixture();
        AtomicInteger closeCalls = new AtomicInteger();
        BootstrapResources resources = new BootstrapResources(noOpHooks());
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
            monitorViolation = catchThrowable(() -> fixture.transitions().completeActivationFailure(
                    classified,
                    resources,
                    activationCause,
                    null,
                    BATCH_ID,
                    fixture.evidence()
            ));
        }

        // then
        assertThat(monitorViolation)
                .isInstanceOf(IllegalMonitorStateException.class)
                .hasMessage("Activation rollback must run outside the bootstrap lifecycle monitor");
        assertThat(closeCalls).hasValue(0);
        resources.close();
        assertThat(closeCalls).hasValue(1);
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
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(
                lifecycle,
                journal,
                new DiagnosticReadinessHandle()
        );
        lifecycle.commitActive(mock(AutoconfigureSession.class));
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

    private static ShutdownHookRegistry noOpHooks() {
        return (threadName, action) -> () -> {
        };
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

    private static ThreadInfo awaitBlocked(Thread blocked, Thread owner, String description) {
        return awaitBlocked(blocked, owner, null, description);
    }

    private static ThreadInfo awaitBlocked(Thread blocked, Thread owner, Object expectedLock, String description) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        ThreadInfo last = null;
        while (deadline - System.nanoTime() > 0L) {
            last = threads.getThreadInfo(blocked.threadId());
            LockInfo observedLock = last == null ? null : last.getLockInfo();
            boolean expectedLockObserved = expectedLock == null
                    || (observedLock != null
                            && observedLock.getIdentityHashCode() == System.identityHashCode(expectedLock)
                            && observedLock.getClassName().equals(expectedLock.getClass().getName()));
            if (last != null
                    && last.getThreadState() == Thread.State.BLOCKED
                    && last.getLockOwnerId() == owner.threadId()
                    && expectedLockObserved) {
                return last;
            }

            LockSupport.parkNanos(BLOCK_POLL_NANOS);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new AssertionError(description + " wait was interrupted");
            }
        }

        throw new AssertionError(description + " did not block on the expected owner; last thread info: " + last);
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

    private static void assertParticipantsNotDeadlocked(Thread first, Thread second) {
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (deadlocked != null) {
            assertThat(deadlocked).doesNotContain(first.threadId(), second.threadId());
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

    private record TerminalFixture(BootstrapLifecycleState lifecycle,
                                   BootstrapReadinessJournal journal,
                                   BootstrapTerminalTransitions transitions,
                                   ExtensionRuntimeEvidence evidence) {
    }
}
