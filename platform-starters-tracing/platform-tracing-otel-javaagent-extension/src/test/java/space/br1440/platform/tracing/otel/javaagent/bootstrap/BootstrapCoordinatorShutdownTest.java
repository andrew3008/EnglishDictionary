package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.CLOSE_FAILURE_EVIDENCE_GENERATION;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_READY;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.SHUTDOWN_CLEANUP_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.CapturingShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrarTestSupport;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SAME_THREAD)
class BootstrapCoordinatorShutdownTest {

    private static final long WAIT_SECONDS = 5L;

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void graceful_shutdown_closes_without_failure() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();

        try (BootstrapCoordinator coordinator = coordinator().server(server).hooks(hooks).build()) {
            AutoconfigureSession session = activate(coordinator);
            ExtensionReadinessWireSnapshot ready = coordinator.currentWire();

            // when
            hooks.runHook();

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSED);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_READY);
            assertThat(coordinator.currentWire().primaryFailureCode()).isEmpty();
            assertThat(coordinator.currentWire().failureCode()).isEmpty();
            assertThat(coordinator.currentWire().evidenceGeneration()).isEqualTo(1L);
            assertThat(coordinator.currentWire().batchId()).isEqualTo(ready.batchId());
            assertThat(session.readiness().snapshot().lifecycle().name()).isEqualTo(WIRE_READY);
            assertThatDiagnosticMBean(server).isNotRegistered();
            assertThat(hooks.registrationCount()).isEqualTo(1);
            assertNoCloseFailureLogs();
        }
    }

    @Test
    void graceful_shutdown_closes_scrubbing_runtime() {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        //noinspection resource — stubbing buildRuntime не получает ресурс; close() выполняет координатор
        doReturn(scrubbingRuntime).when(scrubberFactory).buildRuntime(any());

        try (BootstrapCoordinator coordinator = coordinator()
                .hooks(hooks)
                .scrubberFactory(scrubberFactory)
                .build()) {
            activate(coordinator);

            // when
            hooks.runHook();

            // then
            verify(scrubbingRuntime).close();
        }
    }

    @Test
    void shutdown_withdraws_readiness_before_runtime_controls() {
        // given
        MBeanServer delegate = MBeanServerFactory.newMBeanServer();
        AtomicBoolean firstRuntimeObservedWithdrawal = new AtomicBoolean();
        AtomicInteger runtimeUnregisterCalls = new AtomicInteger();
        MBeanServer server = interceptingServer(delegate, (actual, name) -> {
            if (isRuntimeMBean(name)) {
                runtimeUnregisterCalls.incrementAndGet();
                firstRuntimeObservedWithdrawal.compareAndSet(
                        false,
                        !actual.isRegistered(DomainObjectNames.EXTENSION_READINESS)
                );
            }
            actual.unregisterMBean(name);
        });
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();

        try (BootstrapCoordinator coordinator = coordinator().server(server).hooks(hooks).build()) {
            activate(coordinator);

            // when
            hooks.runHook();

            // then
            assertThat(runtimeUnregisterCalls).hasPositiveValue();
            assertThat(firstRuntimeObservedWithdrawal).isTrue();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSED);
            assertThat(hooks.registrationCount()).isEqualTo(1);
            assertNoCloseFailureLogs();
        }
    }

    @Test
    void failed_readiness_withdrawal_publishes_failed_before_runtime_teardown() throws Exception {
        // given
        MBeanServer delegate = MBeanServerFactory.newMBeanServer();
        IllegalStateException withdrawalFailure = new IllegalStateException("Readiness withdrawal failed");
        CountDownLatch runtimeCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseRuntimeClose = new CountDownLatch(1);
        MBeanServer server = interceptingServer(delegate, (actual, name) -> {
            if (DomainObjectNames.EXTENSION_READINESS.equals(name)) {
                throw withdrawalFailure;
            }
            runtimeCloseEntered.countDown();
            awaitLatch(releaseRuntimeClose, "runtime cleanup release");
            actual.unregisterMBean(name);
        });
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = coordinator().server(server).hooks(hooks).build();
        AutoconfigureSession session = activate(coordinator);
        ExtensionReadinessWireSnapshot ready = coordinator.currentWire();
        FutureTask<Throwable> hookTask = taskCapturing(hooks::runHook);
        Thread hookThread = daemonThread(hookTask);

        // when
        hookThread.start();
        awaitLatch(runtimeCloseEntered, "runtime cleanup entry");

        // then
        try {
            CompositeData live = readinessSnapshot(server);
            assertThat(live.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE)).isEqualTo(WIRE_FAILED);
            assertThat(live.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE))
                    .isEqualTo(SHUTDOWN_CLEANUP_FAILED);
            assertThat(live.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE))
                    .isEqualTo(SHUTDOWN_CLEANUP_FAILED);
            assertThat(live.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID)).isEqualTo(ready.batchId());
            assertThat(live.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION))
                    .isEqualTo(CLOSE_FAILURE_EVIDENCE_GENERATION);
            assertThat(coordinator.currentWire().evidence()).isEqualTo(ready.evidence());
            assertThat(session.readiness().snapshot().lifecycle().name()).isEqualTo(WIRE_FAILED);
        } finally {
            releaseRuntimeClose.countDown();
        }

        assertThat(awaitTask(hookTask)).isNull();
        assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
        assertSingleCloseFailureLog("diagnostic-readiness");
    }

    @Test
    void withdrawal_and_failure_publication_failure_still_releases_runtime() {
        // given
        MBeanServer delegate = MBeanServerFactory.newMBeanServer();
        IllegalStateException withdrawalFailure = new IllegalStateException("Readiness withdrawal failed");
        AtomicInteger runtimeUnregisterCalls = new AtomicInteger();
        MBeanServer server = interceptingServer(delegate, (actual, name) -> {
            if (DomainObjectNames.EXTENSION_READINESS.equals(name)) {
                throw withdrawalFailure;
            }

            runtimeUnregisterCalls.incrementAndGet();
            actual.unregisterMBean(name);
        });
        PlatformExtensionReadiness readiness = spy(PlatformExtensionReadiness.forCurrentExtension());
        IllegalStateException publicationFailure = new IllegalStateException("Failure publication failed");
        AtomicInteger publicationAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            publicationAttempts.incrementAndGet();
            throw publicationFailure;
        }).when(readiness).fail(anyString(), anyString());
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = PlatformTracingMBeanRegistrarTestSupport.create(
                server,
                readiness,
                agentInstanceId
        );
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = coordinator()
                .agentInstanceId(agentInstanceId)
                .registrar(registrar)
                .hooks(hooks)
                .build();
        activate(coordinator);

        // when
        Throwable firstOutcome = catchThrowable(coordinator::close);

        // then
        assertThat(firstOutcome).isNotNull();
        assertThat(BootstrapCleanupFailures.contains(firstOutcome, withdrawalFailure)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(firstOutcome, publicationFailure)).isTrue();
        assertThat(publicationAttempts).hasValue(2);
        assertThat(runtimeUnregisterCalls).hasPositiveValue();
        assertThatDiagnosticMBean(server)
                .isRegistered()
                .hasSnapshotEntry(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE, WIRE_READY);
        assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
        assertSingleCloseFailureLog("diagnostic-readiness");

        int logCount = closeFailureLogCount();
        int runtimeUnregisterCount = runtimeUnregisterCalls.get();
        assertThat(catchThrowable(coordinator::close)).isSameAs(firstOutcome);
        assertThatCode(hooks::runHook).doesNotThrowAnyException();
        assertThat(publicationAttempts).hasValue(2);
        assertThat(runtimeUnregisterCalls).hasValue(runtimeUnregisterCount);
        assertThat(closeFailureLogCount()).isEqualTo(logCount);
    }

    @Test
    void unregister_exception_after_actual_removal_does_not_imply_live_endpoint() {
        // given
        MBeanServer delegate = MBeanServerFactory.newMBeanServer();
        IllegalStateException withdrawalFailure = new IllegalStateException("Post-unregister failure");
        AtomicBoolean injected = new AtomicBoolean();
        AtomicInteger runtimeUnregisterCalls = new AtomicInteger();
        MBeanServer server = interceptingServer(delegate, (actual, name) -> {
            actual.unregisterMBean(name);
            if (DomainObjectNames.EXTENSION_READINESS.equals(name) && injected.compareAndSet(false, true)) {
                throw withdrawalFailure;
            }
            if (isRuntimeMBean(name)) {
                runtimeUnregisterCalls.incrementAndGet();
            }
        });
        BootstrapCoordinator coordinator = coordinator().server(server).build();
        activate(coordinator);

        // when
        Throwable closeFailure = catchThrowable(coordinator::close);

        // then
        assertThat(closeFailure).isNotNull();
        assertThat(BootstrapCleanupFailures.contains(closeFailure, withdrawalFailure)).isTrue();
        assertThat(runtimeUnregisterCalls).hasPositiveValue();
        assertThatDiagnosticMBean(server).isNotRegistered();
        assertThatThrownBy(() -> readinessSnapshot(server)).isInstanceOf(InstanceNotFoundException.class);
        assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertThat(coordinator.currentWire().failureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertSingleCloseFailureLog("diagnostic-readiness");
    }

    @Test
    void shutdown_cleanup_failure_publishes_shutdown_failure_and_replays_cached_outcome() {
        // given
        MBeanServer delegate = MBeanServerFactory.newMBeanServer();
        IllegalStateException runtimeFailure = new IllegalStateException("Runtime cleanup failed");
        AtomicBoolean injected = new AtomicBoolean();
        AtomicInteger runtimeUnregisterCalls = new AtomicInteger();
        MBeanServer server = interceptingServer(delegate, (actual, name) -> {
            actual.unregisterMBean(name);
            if (isRuntimeMBean(name)) {
                runtimeUnregisterCalls.incrementAndGet();
                if (injected.compareAndSet(false, true)) {
                    throw runtimeFailure;
                }
            }
        });
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = coordinator().server(server).hooks(hooks).build();
        AutoconfigureSession session = activate(coordinator);
        ExtensionReadinessWireSnapshot ready = coordinator.currentWire();

        // when
        assertThatCode(hooks::runHook).doesNotThrowAnyException();

        // then
        assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_FAILED);
        assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertThat(coordinator.currentWire().failureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertThat(coordinator.currentWire().evidenceGeneration()).isEqualTo(CLOSE_FAILURE_EVIDENCE_GENERATION);
        assertThat(coordinator.currentWire().batchId()).isEqualTo(ready.batchId());
        assertThat(coordinator.currentWire().evidence()).isEqualTo(ready.evidence());
        assertThat(failureOf(session.readiness().snapshot()).primaryCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertSingleCloseFailureLog("runtime-jmx-controls");

        Throwable cached = catchThrowable(coordinator::close);
        assertThat(BootstrapCleanupFailures.contains(cached, runtimeFailure)).isTrue();
        int unregisterCalls = runtimeUnregisterCalls.get();
        int logCount = closeFailureLogCount();
        assertThatCode(hooks::runHook).doesNotThrowAnyException();
        assertThat(catchThrowable(coordinator::close)).isSameAs(cached);
        assertThat(runtimeUnregisterCalls).hasValue(unregisterCalls);
        assertThat(closeFailureLogCount()).isEqualTo(logCount);
    }

    @Test
    void raw_fatal_cleanup_uses_unknown_resource_label() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        PlatformTracingMBeanRegistrar registrar = spy(PlatformTracingMBeanRegistrarTestSupport.create(
                server,
                readiness,
                agentInstanceId
        ));
        NoClassDefFoundError fatal = new NoClassDefFoundError("Runtime cleanup linkage failed");
        PlatformTracingMBeanRegistrar.RuntimeRegistration runtime = runtimeRegistration(fatal);
        //noinspection resource — регистрацию закрывает координатор
        doReturn(runtime).when(registrar).registerRuntimeControls(any());
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = coordinator()
                .agentInstanceId(agentInstanceId)
                .registrar(registrar)
                .hooks(hooks)
                .build();
        activate(coordinator);

        // when
        assertThatCode(hooks::runHook).doesNotThrowAnyException();

        // then
        assertThat(catchThrowable(coordinator::close)).isSameAs(fatal);
        assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertSingleCloseFailureLog("unknown");
    }

    @Test
    void cleanup_after_published_bootstrap_failure_preserves_primary_failure() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        PlatformTracingMBeanRegistrar registrar = spy(PlatformTracingMBeanRegistrarTestSupport.create(
                server,
                readiness,
                agentInstanceId
        ));
        IllegalStateException cleanupFailure = new IllegalStateException("Runtime cleanup failed");
        //noinspection resource — регистрацию закрывает координатор
        doReturn(runtimeRegistration(cleanupFailure)).when(registrar).registerRuntimeControls(any());
        BootstrapCoordinator coordinator = coordinator()
                .agentInstanceId(agentInstanceId)
                .registrar(registrar)
                .build();
        activate(coordinator);
        IllegalArgumentException activationCause = new IllegalArgumentException("Activation failed");
        BootstrapException activationFailure = catchBootstrapFailure(
                () -> coordinator.execute(ACTIVATION_FAILED, () -> {
                    throw activationCause;
                }),
                ACTIVATION_FAILED
        );

        // when
        Throwable closeFailure = catchThrowable(coordinator::close);

        // then
        assertThat(closeFailure).isNotNull();
        assertThat(activationFailure.getCause()).isSameAs(activationCause);
        assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(coordinator.currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(BootstrapCleanupFailures.contains(activationCause, cleanupFailure)).isTrue();
        BootstrapException replay = catchBootstrapFailure(
                () -> coordinator.execute("AFTER_CLOSE", () -> null),
                FAILED_ROLLBACK_INCOMPLETE
        );
        assertThat(replay.getCause()).isSameAs(activationCause);
        assertSingleCloseFailureLog(FAILED_ROLLBACK_INCOMPLETE, "runtime-jmx-controls");
    }

    @Test
    void close_before_activation_publishes_diagnostic_cleanup_failure() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        PlatformTracingMBeanRegistrar registrar = spy(PlatformTracingMBeanRegistrarTestSupport.create(
                server,
                readiness,
                agentInstanceId
        ));
        UnsupportedOperationException diagnosticFailure =
                new UnsupportedOperationException("Diagnostic readiness cleanup failed");
        AutoCloseable diagnosticRegistration = () -> {
            throw diagnosticFailure;
        };
        //noinspection resource — регистрацию закрывает координатор
        doReturn(diagnosticRegistration).when(registrar).registerDiagnosticReadiness(any());
        BootstrapCoordinator coordinator = coordinator()
                .agentInstanceId(agentInstanceId)
                .registrar(registrar)
                .build();
        coordinator.prepare(config());

        // when
        Throwable closeFailure = catchThrowable(coordinator::close);

        // then
        assertThat(closeFailure).isNotNull();
        assertThat(BootstrapCleanupFailures.contains(closeFailure, diagnosticFailure)).isTrue();
        assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertThat(coordinator.currentWire().failureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertSingleCloseFailureLog("diagnostic-readiness");
    }

    @Test
    void process_lifetime_hook_after_explicit_close_replays_without_side_effects() {
        // given
        MBeanServer delegate = MBeanServerFactory.newMBeanServer();
        AtomicInteger unregisterCalls = new AtomicInteger();
        MBeanServer server = interceptingServer(delegate, (actual, name) -> {
            unregisterCalls.incrementAndGet();
            actual.unregisterMBean(name);
        });
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = coordinator().server(server).hooks(hooks).build();
        activate(coordinator);

        // when
        coordinator.close();
        int completedUnregisterCalls = unregisterCalls.get();
        int completedLogCount = logs.events().size();
        hooks.runHook();
        coordinator.close();

        // then
        assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSED);
        assertThat(unregisterCalls).hasValue(completedUnregisterCalls);
        assertThat(logs.events()).hasSize(completedLogCount);
        assertThat(hooks.registrationCount()).isEqualTo(1);
    }

    private AutoconfigureSession activate(BootstrapCoordinator coordinator) {
        AutoconfigureSession session = coordinator.prepare(config());
        satisfyReadinessBarrier(session);
        try (SdkTracerProvider tracerProvider = providerFor(session)) {
            coordinator.activate(tracerProvider);
        }
        return session;
    }

    private void assertNoCloseFailureLogs() {
        assertThat(closeFailureLogCount()).isZero();
    }

    private void assertSingleCloseFailureLog(String resourceLabel) {
        assertSingleCloseFailureLog(SHUTDOWN_CLEANUP_FAILED, resourceLabel);
    }

    private void assertSingleCloseFailureLog(String failureCode, String resourceLabel) {
        assertThat(logs.events())
                .filteredOn(event -> "BOOTSTRAP_PHASE_FAILED".equals(event.eventName())
                        && "close".equals(event.fields().get("phase")))
                .singleElement()
                .satisfies(event -> assertThat(event.fields())
                        .containsEntry("failureCode", failureCode)
                        .containsEntry("phase", "close")
                        .containsEntry("subphase", "close")
                        .containsEntry("resourceLabel", resourceLabel));
    }

    private int closeFailureLogCount() {
        return (int) logs.events().stream()
                .filter(event -> "BOOTSTRAP_PHASE_FAILED".equals(event.eventName()))
                .filter(event -> "close".equals(event.fields().get("phase")))
                .count();
    }

    private static PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration(Throwable failure) {
        UUID batchId = UUID.randomUUID();
        return new PlatformTracingMBeanRegistrar.RuntimeRegistration() {

            @Override
            public UUID batchId() {
                return batchId;
            }

            @Override
            public void close() {
                if (failure instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) failure;
            }
        };
    }

    private static MBeanServer interceptingServer(MBeanServer delegate, UnregisterInterceptor interceptor) {
        return (MBeanServer) Proxy.newProxyInstance(
                BootstrapCoordinatorShutdownTest.class.getClassLoader(),
                new Class<?>[] {MBeanServer.class},
                (proxy, method, arguments) -> {
                    if ("unregisterMBean".equals(method.getName())
                            && arguments != null
                            && arguments.length == 1
                            && arguments[0] instanceof ObjectName name) {
                        interceptor.unregister(delegate, name);
                        return null;
                    }

                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
    }

    private static CompositeData readinessSnapshot(MBeanServer server) throws Exception {
        return (CompositeData) server.getAttribute(
                DomainObjectNames.EXTENSION_READINESS,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
        );
    }

    private static boolean isRuntimeMBean(ObjectName name) {
        return !DomainObjectNames.EXTENSION_READINESS.equals(name);
    }

    private static FutureTask<Throwable> taskCapturing(Runnable action) {
        return new FutureTask<>(() -> catchThrowable(action::run));
    }

    private static Thread daemonThread(FutureTask<?> task) {
        return Thread.ofPlatform().daemon().name("failed-withdrawal-hook").unstarted(task);
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

    private static <T> T awaitTask(FutureTask<T> task) {
        try {
            return task.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("shutdown hook was interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new AssertionError("shutdown hook failed", failure.getCause());
        } catch (TimeoutException timeout) {
            throw new AssertionError("shutdown hook did not finish within " + WAIT_SECONDS + " seconds", timeout);
        }
    }

    @FunctionalInterface
    private interface UnregisterInterceptor {

        void unregister(MBeanServer delegate, ObjectName name) throws Exception;
    }
}
