package space.br1440.platform.tracing.autoconfigure.support;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxObjectNames;

import javax.management.MBeanServer;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Изолированный test-only стенд application-side контракта Controlled Agent.
 */
public final class ControlledAgentTestRuntime implements AutoCloseable {

    private static final ReentrantLock GLOBAL_RUNTIME_LOCK = new ReentrantLock();
    private static final Set<String> COMPLETE_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    private final MBeanServer server;
    private final SdkTracerProvider tracerProvider;
    private final InMemorySpanExporter spanExporter;

    private ControlledAgentTestRuntime(
            MBeanServer server,
            SdkTracerProvider tracerProvider,
            InMemorySpanExporter spanExporter) {
        this.server = server;
        this.tracerProvider = tracerProvider;
        this.spanExporter = spanExporter;
    }

    public static ControlledAgentTestRuntime start() {
        return start(null);
    }

    private static ControlledAgentTestRuntime start(InMemorySpanExporter spanExporter) {
        GLOBAL_RUNTIME_LOCK.lock();
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        var tracerProviderBuilder = SdkTracerProvider.builder();
        if (spanExporter != null) {
            tracerProviderBuilder.addSpanProcessor(SimpleSpanProcessor.create(spanExporter));
        }
        SdkTracerProvider tracerProvider = tracerProviderBuilder.build();
        try {
            if (server.isRegistered(PlatformTracingJmxObjectNames.EXTENSION_READINESS)) {
                throw new IllegalStateException("Controlled Agent readiness MBean is already registered");
            }
            GlobalOpenTelemetry.resetForTest();
            GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
            server.registerMBean(
                    new StandardMBean(new ReadyExtension(), ReadyExtensionMBean.class),
                    PlatformTracingJmxObjectNames.EXTENSION_READINESS);
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
        ControlledAgentTestRuntime runtime = start(exporter);
        context.getBeanFactory().registerSingleton("controlledAgentSpanExporter", exporter);
        closeWithContext(context, runtime);
    }

    private static void closeWithContext(
            ConfigurableApplicationContext context,
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
            if (server.isRegistered(PlatformTracingJmxObjectNames.EXTENSION_READINESS)) {
                server.unregisterMBean(PlatformTracingJmxObjectNames.EXTENSION_READINESS);
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

    public interface ReadyExtensionMBean {
        String getExtensionVersion();

        int getProtocolVersion();

        String getProfile();

        String getLifecycleState();

        String getFailureCode();

        String getFailureMessage();

        String[] getCapabilities();

        boolean isSanitizerInstalled();

        boolean isSamplerInstalled();

        boolean isRequiredSpanProcessorsInstalled();

        boolean isPropagationHooksInstalled();

        boolean isExportPathProtected();
    }

    public static final class ReadyExtension implements ReadyExtensionMBean {

        @Override
        public String getExtensionVersion() {
            return "development";
        }

        @Override
        public int getProtocolVersion() {
            return 1;
        }

        @Override
        public String getProfile() {
            return "platform-agent-secure-v1";
        }

        @Override
        public String getLifecycleState() {
            return "READY";
        }

        @Override
        public String getFailureCode() {
            return "";
        }

        @Override
        public String getFailureMessage() {
            return "";
        }

        @Override
        public String[] getCapabilities() {
            return COMPLETE_CAPABILITIES.toArray(String[]::new);
        }

        @Override
        public boolean isSanitizerInstalled() {
            return true;
        }

        @Override
        public boolean isSamplerInstalled() {
            return true;
        }

        @Override
        public boolean isRequiredSpanProcessorsInstalled() {
            return true;
        }

        @Override
        public boolean isPropagationHooksInstalled() {
            return true;
        }

        @Override
        public boolean isExportPathProtected() {
            return true;
        }
    }
}
