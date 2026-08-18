package space.br1440.platform.tracing.otel.javaagent.bootstrap;

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
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.EmitResult;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

@UtilityClass
final class TracePipelineSpiAttestation {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(TracePipelineSpiAttestation.class);
    private static final AtomicBoolean EVIDENCE_EMITTED = new AtomicBoolean();

    static final String LEGACY_TRACER_PROVIDER_CONFIGURER_SPI =
            "io.opentelemetry.sdk.autoconfigure.spi.traces.SdkTracerProviderConfigurer";

    static void attest(int platformOrder, Class<?> platformProviderType) {
        attest(platformOrder, platformProviderType, platformProviderType.getClassLoader());
    }

    static void attest(int platformOrder, Class<?> platformProviderType, ClassLoader classLoader) {
        Set<String> upstreamNames = attestAutoConfigurationProviders(
                platformOrder, platformProviderType, classLoader);
        attestNoLegacyTracerProviderConfigurers(classLoader);
        maybePublishEvidence(upstreamNames);
    }

    static void resetEvidenceMarkerForTests() {
        EVIDENCE_EMITTED.set(false);
    }

    private static Set<String> attestAutoConfigurationProviders(int platformOrder,
                                                                Class<?> platformProviderType,
                                                                ClassLoader classLoader) {
        List<ServiceLoader.Provider<AutoConfigurationCustomizerProvider>> providers =
                discover(AutoConfigurationCustomizerProvider.class, classLoader);

        int platformOccurrences = 0;
        Set<String> seenNames = new HashSet<>();
        TreeSet<String> upstreamNames = new TreeSet<>(PlatformOtelAcpPinHashes.NAME_ORDER);
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

            if (isAllowlistedUpstreamProviderName(binaryName)) {
                upstreamNames.add(binaryName);
                continue;
            }

            log.error(AgentEventCodes.BOOT_FORBIDDEN_SPI,
                    "Unknown AutoConfigurationCustomizerProvider is not in the pinned upstream set",
                    "name", binaryName);

            fail("Unknown AutoConfigurationCustomizerProvider " + binaryName
                    + " is not in the pinned upstream AutoConfigurationCustomizerProvider set; "
                    + "the final exporter chain cannot be attested");
        }

        if (platformOccurrences != 1) {
            fail("Platform AutoConfigurationCustomizerProvider " + platformProviderType.getName()
                    + " must be classloader-visible exactly once, found " + platformOccurrences);
        }

        return Set.copyOf(upstreamNames);
    }

    private static void attestNoLegacyTracerProviderConfigurers(ClassLoader classLoader) {
        List<String> providerNames = discoverServiceProviderNames(
                LEGACY_TRACER_PROVIDER_CONFIGURER_SPI, classLoader);

        if (providerNames.isEmpty()) {
            return;
        }

        log.error(AgentEventCodes.BOOT_FORBIDDEN_SPI, "SdkTracerProviderConfigurer is forbidden",
                "name", providerNames.toString());

        fail("SdkTracerProviderConfigurer is forbidden in the secure Controlled Agent profile: " + providerNames);
    }

    private static void maybePublishEvidence(Set<String> upstreamNames) {
        SpiAttestationMode mode = SpiAttestationMode.fromSystemProperty();
        if (mode == SpiAttestationMode.PRODUCTION) {
            return;
        }

        if (mode == SpiAttestationMode.EXACT_EVIDENCE) {
            if (!upstreamNames.equals(PinnedUpstreamAcpProviders.NAMES)) {
                fail("Runtime-visible upstream AutoConfigurationCustomizerProvider set does not "
                        + "equal the generated pin");
            }
        }

        publishEvidence(mode, upstreamNames);
    }

    private static void publishEvidence(SpiAttestationMode mode, Set<String> upstreamNames) {
        if (!EVIDENCE_EMITTED.compareAndSet(false, true)) {
            fail("SPI attestation evidence marker already emitted");
        }

        String setSha256 = PlatformOtelAcpPinHashes.sha256Hex(upstreamNames);
        String pinSha256 = (mode == SpiAttestationMode.EXACT_EVIDENCE)
                ? PinnedUpstreamAcpProviders.PIN_SHA256
                : setSha256;
        String pinCount = (mode == SpiAttestationMode.EXACT_EVIDENCE)
                ? String.valueOf(PinnedUpstreamAcpProviders.PIN_COUNT)
                : String.valueOf(upstreamNames.size());
        List<String> fields = new ArrayList<>();
        fields.add("pin_format_version");
        fields.add(String.valueOf(PlatformOtelAcpPinHashes.FORMAT_VERSION));
        fields.add("pin_count");
        fields.add(pinCount);
        fields.add("pin_sha256");
        fields.add(pinSha256);
        fields.add("set_sha256");
        fields.add(setSha256);
        if (mode == SpiAttestationMode.EXACT_EVIDENCE) {
            fields.add("upstream_agent_sha256");
            fields.add(PinnedUpstreamAcpProviders.UPSTREAM_AGENT_SHA256);
        }

        EmitResult result = log.error(
                AgentEventCodes.BOOT_SPI_ATTESTATION_EVIDENCE,
                "Trace pipeline SPI attestation evidence",
                fields.toArray()
        );
        if (result != EmitResult.EMITTED) {
            fail("SPI attestation evidence marker was not emitted: " + result);
        }
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

    /**
     * Сверяет только binary name с generated pin. Происхождение class-файла не проверяется.
     */
    static boolean isAllowlistedUpstreamProviderName(String providerName) {
        return PinnedUpstreamAcpProviders.NAMES.contains(providerName);
    }

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
                new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int commentStart = line.indexOf('#');
                String providerName = (commentStart >= 0 ? line.substring(0, commentStart) : line).trim();
                if (!providerName.isEmpty()) {
                    providerNames.add(providerName);
                }
            }
        }
    }
}
