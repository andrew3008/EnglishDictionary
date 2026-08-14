package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.traces.SdkTracerProviderConfigurer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
final class TracePipelineSpiAttestation {

    static void attest(int platformOrder, Class<?> platformProviderType) {
        attest(platformOrder, platformProviderType, platformProviderType.getClassLoader());
    }

    static void attest(int platformOrder, Class<?> platformProviderType, ClassLoader classLoader) {
        attestAutoConfigurationProviders(platformOrder, platformProviderType, classLoader);
        attestNoTracerProviderConfigurers(classLoader);
    }

    private static void attestAutoConfigurationProviders(int platformOrder,
                                                         Class<?> platformProviderType,
                                                         ClassLoader classLoader) {
        List<ServiceLoader.Provider<AutoConfigurationCustomizerProvider>> providers =
                discover(AutoConfigurationCustomizerProvider.class, classLoader);

        int platformOccurrences = 0;
        for (ServiceLoader.Provider<AutoConfigurationCustomizerProvider> provider : providers) {
            Class<? extends AutoConfigurationCustomizerProvider> type = providerType(provider);

            if (type == platformProviderType) {
                platformOccurrences++;
                int order = instantiate(provider).order();
                if (order != platformOrder) {
                    fail("Platform AutoConfigurationCustomizerProvider " + platformProviderType.getName()
                            + " reports order=" + order + ", expected=" + platformOrder);
                }
                continue;
            }

            log.error("[bootstrap] {}: unknown AutoConfigurationCustomizerProvider {} is "
                            + "not on the closed allowlist for this secure profile; its order() does "
                            + "not prove it cannot mutate the exporter chain from a later "
                            + "properties-customizer callback",
                    SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED, type.getName());

            fail("Unknown AutoConfigurationCustomizerProvider " + type.getName()
                    + " is not on the closed allowlist for this secure profile; the final exporter "
                    + "chain cannot be attested");
        }

        if (platformOccurrences != 1) {
            fail("Platform AutoConfigurationCustomizerProvider " + platformProviderType.getName()
                    + " must be classloader-visible exactly once, found " + platformOccurrences);
        }
    }

    private static void attestNoTracerProviderConfigurers(ClassLoader classLoader) {
        List<ServiceLoader.Provider<SdkTracerProviderConfigurer>> configurers =
                discover(SdkTracerProviderConfigurer.class, classLoader);

        if (configurers.isEmpty()) {
            return;
        }

        List<String> names = configurers.stream()
                .map(TracePipelineSpiAttestation::providerType)
                .map(Class::getName)
                .toList();

        log.error("[bootstrap] {}: classloader-visible SdkTracerProviderConfigurer {} is forbidden "
                        + "in the secure Controlled Agent profile; it is applied after exporter/processor "
                        + "customization and can add a parallel SpanProcessor with an unwrapped exporter, "
                        + "bypassing the platform SafeSpanExporter boundary entirely",
                SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED, names);

        fail("SdkTracerProviderConfigurer is forbidden in the secure Controlled Agent profile: " + names);
    }

    private static <T> Class<? extends T> providerType(ServiceLoader.Provider<T> provider) {
        try {
            return provider.type();
        } catch (ServiceConfigurationError | RuntimeException e) {
            PlatformThrowables.propagateFatalCauseChain(e);
            throw discoveryFailed(e);
        }
    }

    private static <T> T instantiate(ServiceLoader.Provider<T> provider) {
        try {
            return provider.get();
        } catch (ServiceConfigurationError | RuntimeException e) {
            PlatformThrowables.propagateFatalCauseChain(e);
            throw discoveryFailed(e);
        }
    }

    private static void fail(String message) {
        throw new SanitizerBootstrapException(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED, message);
    }

    private static SanitizerBootstrapException discoveryFailed(Throwable cause) {
        return new SanitizerBootstrapException(
                SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED,
                "Failed to enumerate classloader-visible extension SPI providers for final trace "
                        + "pipeline attestation", cause);
    }

    private static <T> List<ServiceLoader.Provider<T>> discover(Class<T> spiType, ClassLoader classLoader) {
        List<ServiceLoader.Provider<T>> providers = new ArrayList<>();
        try {
            ServiceLoader.load(spiType, classLoader).stream().forEach(providers::add);
        } catch (ServiceConfigurationError | RuntimeException e) {
            PlatformThrowables.propagateFatalCauseChain(e);
            throw discoveryFailed(e);
        }

        return providers;
    }
}
