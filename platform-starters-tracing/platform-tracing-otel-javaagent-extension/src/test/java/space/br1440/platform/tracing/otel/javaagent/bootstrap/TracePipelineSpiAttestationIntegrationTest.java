package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle.AutoConfiguredSdkPrivateControlIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.testsupport.BootstrapIsolatedClassLoader;
import space.br1440.platform.tracing.otel.javaagent.testsupport.spi.LowerOrderDelayedMutationProvider;
import space.br1440.platform.tracing.otel.javaagent.testsupport.spi.TracePipelineAttackProbe;
import space.br1440.platform.tracing.otel.javaagent.testsupport.spi.UnsafeTracerProviderConfigurer;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.traces.SdkTracerProviderConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@ExtendWith(AutoConfiguredSdkPrivateControlIsolationExtension.class)
class TracePipelineSpiAttestationIntegrationTest {

    @BeforeEach
    void resetProbe() {
        TracePipelineAttackProbe.reset();
    }

    @Test
    void realBuildFailsBeforeConfigureSdk_whenLowerOrderProviderDelaysExporterMutation(@TempDir Path overlayDir)
            throws IOException {
        ClassLoader serviceClassLoader = isolatedClassLoaderWithOverlay(
                overlayDir, AutoConfigurationCustomizerProvider.class, LowerOrderDelayedMutationProvider.class);

        Throwable failure = catchThrowable(() -> buildSdk(serviceClassLoader));

        assertAttestationFailed(failure);
        assertThat(TracePipelineAttackProbe.ACP_EXPORTER_CUSTOMIZER_CALLED)
                .as("the attacker's addSpanExporterCustomizer callback must never actually run")
                .isFalse();
    }

    @Test
    void realBuildFailsBeforeTracerProviderConfigurerIsApplied_whenForeignConfigurerIsPresent(
            @TempDir Path overlayDir) throws IOException {
        ClassLoader serviceClassLoader = isolatedClassLoaderWithOverlay(
                overlayDir, SdkTracerProviderConfigurer.class, UnsafeTracerProviderConfigurer.class);

        Throwable failure = catchThrowable(() -> buildSdk(serviceClassLoader));

        assertAttestationFailed(failure);
        assertThat(TracePipelineAttackProbe.TRACER_CONFIGURER_CALLED)
                .as("the forbidden configurer's configure(...) — which would add a raw parallel "
                        + "SpanProcessor — must never actually run")
                .isFalse();
    }

    private static void assertAttestationFailed(Throwable failure) {
        SanitizerBootstrapException attestationFailure = findCause(failure, SanitizerBootstrapException.class);
        assertThat(attestationFailure)
                .as("build() must fail with a SanitizerBootstrapException in the cause chain")
                .isNotNull();
        assertThat(attestationFailure.code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            Throwable next = current.getCause();
            current = (next == current) ? null : next;
        }
        return null;
    }

    private static AutoConfiguredOpenTelemetrySdk buildSdk(ClassLoader serviceClassLoader) {
        return AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(serviceClassLoader)
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.traces.exporter", "none"))
                .build();
    }

    private static ClassLoader isolatedClassLoaderWithOverlay(
            Path overlayDir, Class<?> spiType, Class<?> providerType) throws IOException {
        Path servicesDir = overlayDir.resolve("META-INF").resolve("services");
        Files.createDirectories(servicesDir);
        Files.writeString(
                servicesDir.resolve(spiType.getName()),
                providerType.getName(),
                StandardCharsets.UTF_8);

        ClassLoader realClassLoader = TracePipelineSpiAttestationIntegrationTest.class.getClassLoader();
        ClassLoader overlay = new URLClassLoader(new URL[] {overlayDir.toUri().toURL()}, realClassLoader);
        return BootstrapIsolatedClassLoader.freshBootstrapClassLoader(overlay);
    }
}
