package space.br1440.platform.tracing.otel.javaagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.testsupport.BootstrapIsolatedClassLoader;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PlatformExtensionReadinessIntegrationTest {

    @Test
    void default_verifier_fails_sdk_build_in_resource_phase_without_publishing_ready() throws Exception {
        // given
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ClassLoader isolatedLoader = BootstrapIsolatedClassLoader.freshBootstrapClassLoader();
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();

        try (AutoCloseable ignored = bootstrapOwner(isolatedLoader)) {
            // when
            assertThatThrownBy(() -> AutoConfiguredOpenTelemetrySdk.builder()
                    .setServiceClassLoader(isolatedLoader)
                    .addPropertiesSupplier(() -> Map.of(
                            "otel.service.name", "readiness-integration-test",
                            "otel.traces.exporter", "logging",
                            "otel.metrics.exporter", "none",
                            "otel.logs.exporter", "none",
                            "otel.propagators", "tracecontext,baggage,platform-trace-control"))
                    .build())
                    .hasStackTraceContaining("DISTRIBUTION_EVIDENCE_MISSING")
                    .rootCause()
                    .hasMessageContaining("Expected controlled agent SHA-256 is not set");

            // then
            assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();
            CompositeData readiness = readinessSnapshot(server);
            assertThat(readiness.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE))
                    .isEqualTo("FAILED")
                    .isNotEqualTo("READY");
            assertThat(readiness.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE))
                    .isEqualTo("DISTRIBUTION_EVIDENCE_MISSING");
            assertThat((String[]) readiness.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES))
                    .doesNotContain("PLATFORM_SAMPLER_INSTALLED");
        }

        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();
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
