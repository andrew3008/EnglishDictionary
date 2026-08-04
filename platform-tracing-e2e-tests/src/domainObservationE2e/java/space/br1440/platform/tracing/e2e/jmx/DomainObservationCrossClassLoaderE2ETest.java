package space.br1440.platform.tracing.e2e.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxObjectNames;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Обязательный cross-classloader round-trip для шести domain observation: агентские
 * control-классы грузятся из {@code agentExtensionJar} в изолированный {@link URLClassLoader},
 * а {@link PlatformTracingJmxClient} декодирует JDK {@code CompositeData} из application CL.
 * Wire пересекает границу classloader'ов без общего Java-типа observation.
 *
 * <p>Активируется вместе с остальным E2E-сьюитом ({@code -PrunE2e}).
 */
class DomainObservationCrossClassLoaderE2ETest {

    private static final String AGENT_PKG = "space.br1440.platform.tracing.otel.javaagent";

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private URLClassLoader agentClassLoader;

    @AfterEach
    void cleanup() throws Exception {
        for (ObjectName name : new ObjectName[]{
                PlatformTracingJmxObjectNames.SAMPLING,
                PlatformTracingJmxObjectNames.SCRUBBING,
                PlatformTracingJmxObjectNames.VALIDATION,
                PlatformTracingJmxObjectNames.EXPORT,
                PlatformTracingJmxObjectNames.PROCESSOR_METRICS,
                PlatformTracingJmxObjectNames.DIAGNOSTICS
        }) {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        }
        if (agentClassLoader != null) {
            agentClassLoader.close();
        }
    }

    @Test
    void sixDomainObservationsDecodeAcrossClassLoaderBoundaryWithAbsentOwners() throws Exception {
        URL[] agentClasspath = new URL[]{
                Path.of(requiredProperty("smoke.otel.extension.jar")).toUri().toURL(),
                Path.of(requiredProperty("smoke.platform.otel.jar")).toUri().toURL(),
                Path.of(requiredProperty("smoke.platform.api.jar")).toUri().toURL()
        };
        // Parent фильтрует agent-пакеты: observation control грузится только из agentClasspath,
        // а PlatformTracingJmxClient остаётся на application CL тестового модуля.
        agentClassLoader = new URLClassLoader(
                agentClasspath,
                new FilteringClassLoader(getClass().getClassLoader(), AGENT_PKG));

        UUID agentInstanceId = UUID.randomUUID();
        registerAgentMBean(agentClassLoader,
                AGENT_PKG + ".jmx.sampling.PlatformSamplingControl",
                PlatformTracingJmxObjectNames.SAMPLING,
                createSamplingControl(agentClassLoader, agentInstanceId));
        registerAgentMBean(agentClassLoader,
                AGENT_PKG + ".jmx.scrubbing.PlatformScrubbingControl",
                PlatformTracingJmxObjectNames.SCRUBBING,
                createSingleSlotControl(agentClassLoader,
                        AGENT_PKG + ".jmx.scrubbing.PlatformScrubbingControl", agentInstanceId));
        registerAgentMBean(agentClassLoader,
                AGENT_PKG + ".jmx.validation.PlatformValidationControl",
                PlatformTracingJmxObjectNames.VALIDATION,
                createSingleSlotControl(agentClassLoader,
                        AGENT_PKG + ".jmx.validation.PlatformValidationControl", agentInstanceId));
        registerAgentMBean(agentClassLoader,
                AGENT_PKG + ".jmx.export.PlatformExportControl",
                PlatformTracingJmxObjectNames.EXPORT,
                createExportControl(agentClassLoader, agentInstanceId));
        registerAgentMBean(agentClassLoader,
                AGENT_PKG + ".jmx.metrics.PlatformProcessorMetricsControl",
                PlatformTracingJmxObjectNames.PROCESSOR_METRICS,
                createProcessorMetricsControl(agentClassLoader, agentInstanceId));
        registerAgentMBean(agentClassLoader,
                AGENT_PKG + ".jmx.diagnostics.PlatformDiagnosticsControl",
                PlatformTracingJmxObjectNames.DIAGNOSTICS,
                createDiagnosticsControl(agentClassLoader, agentInstanceId));

        // Application-CL клиент не разделяет Java-тип observation с agent-CL.
        PlatformTracingJmxClient client = new PlatformTracingJmxClient(server);

        String expectedAgentInstanceId = agentInstanceId.toString();

        Optional<SamplingObservation> sampling = client.sampling().observation();
        assertThat(sampling).isPresent();
        assertThat(sampling.get().decisionCounts()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(sampling.get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.scrubbing().observation()).isPresent();
        assertThat(client.scrubbing().observation().get().owner()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(client.scrubbing().observation().get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.validation().observation()).isPresent();
        assertThat(client.validation().observation().get().owner()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(client.validation().observation().get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        Optional<ExportObservation> export = client.export().observation();
        assertThat(export).isPresent();
        assertThat(export.get().processor()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(export.get().exporter()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(export.get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        Optional<ProcessorMetricsObservation> processor = client.processorMetrics().observation();
        assertThat(processor).isPresent();
        assertThat(processor.get().watchdog()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(processor.get().composite()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(processor.get().metrics()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(processor.get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.diagnostics().observation()).isPresent();
        assertThat(client.diagnostics().observation().get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);
        assertThat(client.jmxContractDiagnostics()).isEmpty();
    }

    private static Object createSamplingControl(ClassLoader cl, UUID agentInstanceId) throws Exception {
        Class<?> acceptedPolicyAccess = Class.forName(AGENT_PKG + ".control.state.AcceptedPolicyAccess", true, cl);
        Object acceptedPolicy = acceptedPolicyAccess
                .getMethod("isolatedSampling", boolean.class, java.util.List.class, java.util.List.class,
                        java.util.Map.class, double.class)
                .invoke(null, true, java.util.List.of(), java.util.List.of(), java.util.Map.of(), 0.5d);
        Object slot = newOwnerSlot(cl);
        Class<?> controlClass = Class.forName(AGENT_PKG + ".jmx.sampling.PlatformSamplingControl", true, cl);
        Constructor<?> ctor = controlClass.getConstructors()[0];
        return ctor.newInstance(acceptedPolicy, slot, agentInstanceId);
    }

    private static Object createSingleSlotControl(ClassLoader cl, String controlFqcn, UUID agentInstanceId)
            throws Exception {
        Class<?> controlClass = Class.forName(controlFqcn, true, cl);
        Constructor<?> ctor = controlClass.getConstructors()[0];
        return ctor.newInstance(newOwnerSlot(cl), agentInstanceId);
    }

    private static Object createExportControl(ClassLoader cl, UUID agentInstanceId) throws Exception {
        Class<?> controlClass = Class.forName(AGENT_PKG + ".jmx.export.PlatformExportControl", true, cl);
        Constructor<?> ctor = controlClass.getConstructors()[0];
        return ctor.newInstance(newOwnerSlot(cl), newOwnerSlot(cl), agentInstanceId);
    }

    private static Object createProcessorMetricsControl(ClassLoader cl, UUID agentInstanceId) throws Exception {
        Class<?> controlClass = Class.forName(AGENT_PKG + ".jmx.metrics.PlatformProcessorMetricsControl", true, cl);
        Constructor<?> ctor = controlClass.getConstructors()[0];
        return ctor.newInstance(newOwnerSlot(cl), newOwnerSlot(cl), newOwnerSlot(cl), agentInstanceId);
    }

    private static Object createDiagnosticsControl(ClassLoader cl, UUID agentInstanceId) throws Exception {
        Class<?> controlClass = Class.forName(AGENT_PKG + ".jmx.diagnostics.PlatformDiagnosticsControl", true, cl);
        Constructor<?> ctor = controlClass.getConstructors()[0];
        return ctor.newInstance(new LongAdder(), agentInstanceId);
    }

    private static Object newOwnerSlot(ClassLoader cl) throws Exception {
        Class<?> slotClass = Class.forName(AGENT_PKG + ".jmx.observation.OwnerRegistrationSlot", true, cl);
        return slotClass.getConstructor().newInstance();
    }

    private void registerAgentMBean(ClassLoader cl, String controlFqcn, ObjectName name, Object impl)
            throws Exception {
        assertThat(impl.getClass().getClassLoader()).isSameAs(cl);
        assertThat(impl.getClass().getName()).isEqualTo(controlFqcn);
        server.registerMBean(impl, name);
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        assertThat(value).as(key).isNotBlank();
        return value;
    }

    /**
     * Parent, который не отдаёт agent-пакеты — они обязаны грузиться из extension JAR
     * целевого {@link URLClassLoader}. JDK и прочие зависимости остаются на parent.
     */
    private static final class FilteringClassLoader extends ClassLoader {
        private final String blockedPrefix;

        FilteringClassLoader(ClassLoader parent, String blockedPrefix) {
            super(parent);
            this.blockedPrefix = blockedPrefix;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(blockedPrefix)) {
                throw new ClassNotFoundException("filtered: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
