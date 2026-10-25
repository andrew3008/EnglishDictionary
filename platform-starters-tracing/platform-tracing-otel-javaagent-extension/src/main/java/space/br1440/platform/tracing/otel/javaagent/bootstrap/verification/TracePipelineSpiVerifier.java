package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracePipelineSpiVerifier {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(TracePipelineSpiVerifier.class);

    static final String LEGACY_TRACER_PROVIDER_CONFIGURER_SPI =
            "io.opentelemetry.sdk.autoconfigure.spi.traces.SdkTracerProviderConfigurer";

    public static void verify(int platformOrder, Class<?> platformProviderType) {
        verify(platformOrder, platformProviderType, platformProviderType.getClassLoader());
    }

    static void verify(int platformOrder, Class<?> platformProviderType, ClassLoader classLoader) {
        verifyAutoConfigurationProviders(platformOrder, platformProviderType, classLoader);
        verifyNoLegacyTracerProviderConfigurers(classLoader);
    }

    private static void verifyAutoConfigurationProviders(int platformOrder,
                                                         Class<?> platformProviderType,
                                                         ClassLoader classLoader) {
        List<ServiceLoader.Provider<AutoConfigurationCustomizerProvider>> providers =
                discover(AutoConfigurationCustomizerProvider.class, classLoader);

        int platformOccurrences = 0;
        Set<String> seenNames = new HashSet<>();
        Set<String> allowedProviderNames = allowedProviderNames();
        for (ServiceLoader.Provider<AutoConfigurationCustomizerProvider> provider : providers) {
            Class<? extends AutoConfigurationCustomizerProvider> type = providerType(provider);
            String binaryName = type.getName();

            if (!seenNames.add(binaryName)) {
                fail("Duplicate runtime-visible AutoConfigurationCustomizerProvider " + binaryName);
            }

            if (type == platformProviderType) {
                platformOccurrences++;
                int order = instantiate(provider).order();
                if (order != platformOrder) {
                    fail("Platform AutoConfigurationCustomizerProvider " + platformProviderType.getName()
                            + " reports order=" + order + ", expected=" + platformOrder);
                }
                continue;
            }

            if (allowedProviderNames.contains(binaryName)) {
                continue;
            }

            log.log(BootstrapLogEvent.UNKNOWN_AUTO_CONFIGURATION_CUSTOMIZER_PROVIDER_FORBIDDEN,
                    fields -> fields.putString(AgentLogField.NAME, binaryName));

            fail("Unknown AutoConfigurationCustomizerProvider " + binaryName
                    + " is not in the allowed upstream OTel Java Agent "
                    + "AutoConfigurationCustomizerProvider set; the final exporter chain cannot be verified");
        }

        if (platformOccurrences != 1) {
            fail("Platform AutoConfigurationCustomizerProvider " + platformProviderType.getName()
                    + " must be classloader-visible exactly once, found " + platformOccurrences);
        }
    }

    private static void verifyNoLegacyTracerProviderConfigurers(ClassLoader classLoader) {
        List<String> providerNames = discoverServiceProviderNames(
                LEGACY_TRACER_PROVIDER_CONFIGURER_SPI, classLoader);

        if (providerNames.isEmpty()) {
            return;
        }

        log.log(BootstrapLogEvent.SDK_TRACER_PROVIDER_CONFIGURER_FORBIDDEN,
                fields -> fields.putString(AgentLogField.NAME, providerNames.toString()));

        fail("SdkTracerProviderConfigurer is forbidden in the secure Controlled Agent profile: " + providerNames);
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
                        + "pipeline verification", cause);
    }

    @SuppressWarnings("SameParameterValue")
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

    private static Set<String> allowedProviderNames() {
        try {
            return UpstreamCustomizerProviderAllowlist.load().providerClassNames();
        } catch (IllegalStateException exception) {
            throw new SanitizerBootstrapException(
                    SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED,
                    exception.getMessage(),
                    exception);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static List<String> discoverServiceProviderNames(String spiName, ClassLoader classLoader) {
        String resourceName = "META-INF/services/" + spiName;
        TreeSet<String> providerNames = new TreeSet<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(resourceName);
            while (resources.hasMoreElements()) {
                readServiceProviderNames(resources.nextElement(), providerNames);
            }
        } catch (IOException | RuntimeException e) {
            PlatformThrowables.propagateFatalCauseChain(e);
            throw discoveryFailed(e);
        }

        return List.copyOf(providerNames);
    }

    private static void readServiceProviderNames(URL resource, TreeSet<String> providerNames) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                int commentStart = line.indexOf('#');
                String providerName = ((commentStart >= 0) ? line.substring(0, commentStart) : line).trim();
                if (!providerName.isEmpty()) {
                    providerNames.add(providerName);
                }
            }
        }
    }
}
