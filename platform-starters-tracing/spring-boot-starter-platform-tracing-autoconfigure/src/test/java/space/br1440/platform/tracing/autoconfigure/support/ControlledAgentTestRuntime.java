package space.br1440.platform.tracing.autoconfigure.support;

import java.lang.management.ManagementFactory;
import java.util.concurrent.locks.ReentrantLock;
import javax.management.DynamicMBean;
import javax.management.MBeanServer;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import space.br1440.platform.tracing.autoconfigure.agent.StarterModuleVersionTestSupport;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

public final class ControlledAgentTestRuntime implements AutoCloseable {

    private static final ReentrantLock GLOBAL_RUNTIME_LOCK = new ReentrantLock();

    private final MBeanServer server;
    private final SdkTracerProvider tracerProvider;
    private final InMemorySpanExporter spanExporter;

    private ControlledAgentTestRuntime(MBeanServer server,
                                       SdkTracerProvider tracerProvider,
                                       InMemorySpanExporter spanExporter) {
        this.server = server;
        this.tracerProvider = tracerProvider;
        this.spanExporter = spanExporter;
    }

    public static ControlledAgentTestRuntime start() {
        return start(null, readyEndpoint());
    }

    private static ControlledAgentTestRuntime start(InMemorySpanExporter spanExporter,
                                                    DynamicMBean readinessEndpoint) {
        GLOBAL_RUNTIME_LOCK.lock();
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        var tracerProviderBuilder = SdkTracerProvider.builder();
        if (spanExporter != null) {
            tracerProviderBuilder.addSpanProcessor(SimpleSpanProcessor.create(spanExporter));
        }
        SdkTracerProvider tracerProvider = tracerProviderBuilder.build();
        try {
            if (server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                throw new IllegalStateException("Controlled Agent readiness MBean is already registered");
            }
            GlobalOpenTelemetry.resetForTest();
            GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
            server.registerMBean(
                    readinessEndpoint,
                    JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
            return new ControlledAgentTestRuntime(server, tracerProvider, spanExporter);
        } catch (Exception failure) {
            tracerProvider.close();
            GlobalOpenTelemetry.resetForTest();
            GLOBAL_RUNTIME_LOCK.unlock();
            throw new IllegalStateException("Cannot start Controlled Agent test runtime", failure);
        }
    }

    public static void initialize(ConfigurableApplicationContext context) {
        ControlledAgentTestRuntime runtime = start();
        closeWithContext(context, runtime);
    }

    public static void initializeWithExporter(ConfigurableApplicationContext context) {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        ControlledAgentTestRuntime runtime = start(
                exporter,
                readyEndpoint());
        context.getBeanFactory().registerSingleton("controlledAgentSpanExporter", exporter);
        closeWithContext(context, runtime);
    }

    public static ControlledAgentTestRuntime startIncompatible() {
        return start(null, ReadinessJmxTestFixtures.endpoint(() -> "not-composite"));
    }

    public static ControlledAgentTestRuntime startFailed(String failureCode) {
        return start(
                null,
                ReadinessJmxTestFixtures.endpoint(
                        () -> ReadinessJmxTestFixtures.failedSnapshot(failureCode)
                )
        );
    }

    private static DynamicMBean readyEndpoint() {
        return ReadinessJmxTestFixtures.endpoint(
                () -> ReadinessJmxTestFixtures.readySnapshotForExtensionVersion(
                        StarterModuleVersionTestSupport.current()
                )
        );
    }

    private static void closeWithContext(ConfigurableApplicationContext context,
                                         ControlledAgentTestRuntime runtime) {
        context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent) {
                runtime.close();
            }
        });
    }

    @Override
    public void close() {
        try {
            if (server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                server.unregisterMBean(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
            }

            tracerProvider.close();
            if (spanExporter != null) {
                spanExporter.close();
            }
            GlobalOpenTelemetry.resetForTest();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot stop Controlled Agent test runtime", failure);
        } finally {
            GLOBAL_RUNTIME_LOCK.unlock();
        }
    }
}
