package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

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

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapAutoConfigurationCustomizerProvider;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.testsupport.BootstrapIsolatedClassLoader;
import space.br1440.platform.tracing.otel.javaagent.testsupport.spi.LegacyTracerProviderConfigurerProbe;
import space.br1440.platform.tracing.otel.javaagent.testsupport.spi.LowerOrderDelayedMutationProvider;
import space.br1440.platform.tracing.otel.javaagent.testsupport.spi.TracePipelineAttackProbe;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class TracePipelineSpiVerifierIntegrationTest {

    private static final int PLATFORM_ORDER = 100;

    @BeforeEach
    void resetProbe() {
        TracePipelineAttackProbe.reset();
    }

    @Test
    void realBuildFailsBeforeConfigureSdk_whenLowerOrderProviderDelaysExporterMutation(@TempDir Path overlayDir) throws IOException {
        // given
        ClassLoader serviceClassLoader = isolatedClassLoaderWithOverlay(
                overlayDir, AutoConfigurationCustomizerProvider.class.getName(),
                LowerOrderDelayedMutationProvider.class
        );

        // when
        Throwable failure = catchThrowable(() -> buildSdk(serviceClassLoader).getOpenTelemetrySdk().close());

        // then
        assertVerificationFailed(failure);
        assertThat(TracePipelineAttackProbe.ACP_EXPORTER_CUSTOMIZER_CALLED)
                .as("the attacker's addSpanExporterCustomizer callback must never actually run")
                .isFalse();
    }

    @Test
    void isolatedServiceClassLoaderRejectsLegacyTracerProviderConfigurerDescriptorWithoutLoadingProvider(@TempDir Path overlayDir) throws IOException {
        // given
        ClassLoader serviceClassLoader = isolatedClassLoaderWithOverlay(
                overlayDir,
                TracePipelineSpiVerifier.LEGACY_TRACER_PROVIDER_CONFIGURER_SPI,
                LegacyTracerProviderConfigurerProbe.class);
        String serviceResource = "META-INF/services/"
                + TracePipelineSpiVerifier.LEGACY_TRACER_PROVIDER_CONFIGURER_SPI;

        assertThat(serviceClassLoader.getResources(serviceResource).hasMoreElements()).isTrue();

        // when
        Throwable failure = catchThrowable(() -> TracePipelineSpiVerifier.verify(
                PLATFORM_ORDER, BootstrapAutoConfigurationCustomizerProvider.class, serviceClassLoader));

        // then
        assertVerificationFailed(failure);
        assertThat(TracePipelineAttackProbe.LEGACY_TRACER_CONFIGURER_CONSTRUCTED)
                .as("the forbidden legacy provider must never be loaded")
                .isFalse();
    }

    private static void assertVerificationFailed(Throwable failure) {
        SanitizerBootstrapException attestationFailure = findCause(failure, SanitizerBootstrapException.class);
        assertThat(attestationFailure)
                .as("build() must fail with a SanitizerBootstrapException in the cause chain")
                .isNotNull();
        assertThat(attestationFailure.code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }

    @SuppressWarnings("SameParameterValue")
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

    private static ClassLoader isolatedClassLoaderWithOverlay(Path overlayDir,
                                                              String spiName,
                                                              Class<?> providerType) throws IOException {
        Path servicesDir = overlayDir.resolve("META-INF").resolve("services");
        Files.createDirectories(servicesDir);
        Files.writeString(
                servicesDir.resolve(spiName),
                providerType.getName(),
                StandardCharsets.UTF_8
        );

        ClassLoader realClassLoader = TracePipelineSpiVerifierIntegrationTest.class.getClassLoader();
        ClassLoader overlay = new URLClassLoader(new URL[] {overlayDir.toUri().toURL()}, realClassLoader);
        return BootstrapIsolatedClassLoader.freshBootstrapClassLoader(overlay);
    }
}
