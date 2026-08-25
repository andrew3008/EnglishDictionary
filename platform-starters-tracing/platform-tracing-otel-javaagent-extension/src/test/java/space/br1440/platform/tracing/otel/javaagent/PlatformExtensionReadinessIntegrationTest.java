package space.br1440.platform.tracing.otel.javaagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import javax.management.MBeanServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle.AutoConfiguredSdkPrivateControlIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.testsupport.BootstrapIsolatedClassLoader;

@ExtendWith(AutoConfiguredSdkPrivateControlIsolationExtension.class)
class PlatformExtensionReadinessIntegrationTest {

    @Test
    void realAutoconfigureCallbacksPublishReadyOnlyForCompletePipeline() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        // Fresh classloader isolates the shared ExtensionRuntime coordinator for this
        // build (r5 plan §3 acceptance #9). Outside a real java agent, nothing invokes
        // AgentListener.afterAgent automatically, so the activation transaction — which now owns
        // diagnostic/runtime MBean registration (r5 plan §6) — is driven explicitly below.
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
            listenerClass.getMethod("afterAgent", AutoConfiguredOpenTelemetrySdk.class)
                    .invoke(listener, sdk);

            assertThat(server.getAttribute(
                    DomainObjectNames.EXTENSION_READINESS, "LifecycleState"))
                    .isEqualTo("READY");
            assertThat((String[]) server.getAttribute(
                    DomainObjectNames.EXTENSION_READINESS, "Capabilities"))
                    .containsExactlyInAnyOrder(
                            "CONFIGURATION_LOADED",
                            "PLATFORM_SAMPLER_INSTALLED",
                            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
                            "SANITIZER_INSTALLED",
                            "PROPAGATION_HOOKS_INSTALLED",
                            "SAFE_EXPORTER_INSTALLED",
                            "EXPORT_PATH_PROTECTED");
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

            assertThatThrownBy(() -> listenerClass
                    .getMethod("afterAgent", AutoConfiguredOpenTelemetrySdk.class)
                    .invoke(listener, sdk))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause()
                    .hasMessageContaining("ACTIVATION_FAILED");

            assertThat(server.getAttribute(DomainObjectNames.EXTENSION_READINESS, "LifecycleState"))
                    .isEqualTo("FAILED");
            assertThat((String[]) server.getAttribute(DomainObjectNames.EXTENSION_READINESS, "Capabilities"))
                    .doesNotContain("SANITIZER_INSTALLED");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }
}
