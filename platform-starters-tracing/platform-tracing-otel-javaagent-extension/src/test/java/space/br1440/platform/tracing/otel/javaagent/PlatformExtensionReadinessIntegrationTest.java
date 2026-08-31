package space.br1440.platform.tracing.otel.javaagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.testsupport.BootstrapIsolatedClassLoader;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class PlatformExtensionReadinessIntegrationTest {

    @Test
    void completeUnpackagedPipelineFailsAttestationBeforePublishingReady() {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ClassLoader isolatedLoader = BootstrapIsolatedClassLoader.freshBootstrapClassLoader();
        AutoConfiguredOpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(isolatedLoader)
                .addPropertiesSupplier(() -> Map.of(
                        "otel.service.name", "readiness-integration-test",
                        "otel.traces.exporter", "logging",
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.propagators", "tracecontext,baggage,platform-trace-control",
                        "platform.tracing.queue.overflow-policy", "UPSTREAM"))
                .build();
        try {
            Class<?> listenerClass = isolatedLoader.loadClass(
                    "space.br1440.platform.tracing.otel.javaagent.bootstrap.ActivationAgentListener");
            Object listener = listenerClass.getDeclaredConstructor().newInstance();

            try (AutoCloseable ignored = bootstrapOwner(isolatedLoader)) {
                assertThatThrownBy(() -> listenerClass
                        .getMethod("afterAgent", AutoConfiguredOpenTelemetrySdk.class)
                        .invoke(listener, sdk))
                        .isInstanceOf(InvocationTargetException.class)
                        .cause()
                        .hasMessageContaining("DEVELOPMENT_VERSION_FORBIDDEN");

                CompositeData readiness = readinessSnapshot(server);
                assertThat(readiness.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE))
                        .isEqualTo("FAILED");
                assertThat(readiness.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE))
                        .isEqualTo("DEVELOPMENT_VERSION_FORBIDDEN");
                assertThat((String[]) readiness.get(
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES))
                        .containsExactlyInAnyOrder(
                                "CONFIGURATION_LOADED",
                                "PLATFORM_SAMPLER_INSTALLED",
                                "REQUIRED_SPAN_PROCESSORS_INSTALLED",
                                "PROPAGATION_HOOKS_INSTALLED",
                                "SAFE_EXPORTER_INSTALLED",
                                "EXPORT_PATH_PROTECTED");
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    void secureProfileWithExporterNoneFailsActivationAndNeverPublishesReady() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ClassLoader isolatedLoader = BootstrapIsolatedClassLoader.freshBootstrapClassLoader();
        AutoConfiguredOpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(isolatedLoader)
                .addPropertiesSupplier(() -> Map.of(
                        "otel.service.name", "exporter-none-secure-profile",
                        "otel.traces.exporter", "none",
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.propagators", "tracecontext,baggage,platform-trace-control"))
                .build();
        try {
            Class<?> listenerClass = isolatedLoader.loadClass(
                    "space.br1440.platform.tracing.otel.javaagent.bootstrap.ActivationAgentListener");
            Object listener = listenerClass.getDeclaredConstructor().newInstance();

            try (AutoCloseable ignored = bootstrapOwner(isolatedLoader)) {
                assertThatThrownBy(() -> listenerClass
                        .getMethod("afterAgent", AutoConfiguredOpenTelemetrySdk.class)
                        .invoke(listener, sdk))
                        .isInstanceOf(InvocationTargetException.class)
                        .cause()
                        .hasMessageContaining("DEVELOPMENT_VERSION_FORBIDDEN");

                CompositeData readiness = readinessSnapshot(server);
                assertThat(readiness.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE))
                        .isEqualTo("FAILED");
                assertThat(readiness.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE))
                        .isEqualTo("DEVELOPMENT_VERSION_FORBIDDEN");
                assertThat((String[]) readiness.get(
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES))
                        .doesNotContain("SANITIZER_INSTALLED");
            }
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    private static CompositeData readinessSnapshot(MBeanServer server) throws Exception {
        return (CompositeData) server.getAttribute(
                DomainObjectNames.EXTENSION_READINESS,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT);
    }

    private static AutoCloseable bootstrapOwner(ClassLoader isolatedLoader) throws ReflectiveOperationException {
        Class<?> runtime = isolatedLoader.loadClass(
                "space.br1440.platform.tracing.otel.javaagent.bootstrap.ExtensionRuntime");
        Method bootstrap = runtime.getDeclaredMethod("bootstrap");
        bootstrap.setAccessible(true);
        return (AutoCloseable) bootstrap.invoke(null);
    }
}
