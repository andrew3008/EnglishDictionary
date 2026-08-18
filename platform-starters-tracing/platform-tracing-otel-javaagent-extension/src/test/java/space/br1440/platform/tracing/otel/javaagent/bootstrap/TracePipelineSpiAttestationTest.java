package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.pin.IndependentUpstreamAcpJarExtractor;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level coverage for {@link TracePipelineSpiAttestation}: generated upstream pin for
 * {@link AutoConfigurationCustomizerProvider} (order-only attestation was a BLOCKER — order()
 * determines only when {@code customize()} runs, not whether a provider can mutate the exporter
 * chain later from a properties-customizer callback) and the empty allowlist for the legacy
 * tracer-provider configurer service descriptor applied after exporter/processor customization.
 *
 * <p>These tests overlay a synthetic {@code META-INF/services} entry on top of the real
 * application classloader (without touching the real registration) and call
 * {@code attest(...)} directly. See {@link TracePipelineSpiAttestationIntegrationTest} for the
 * real {@code AutoConfiguredOpenTelemetrySdk.builder().build()} lifecycle proof that the attacker
 * customizer/configurer is never actually invoked.
 */
class TracePipelineSpiAttestationTest {

    private static final int PLATFORM_ORDER = 100;

    @AfterEach
    void resetAttestationIsolation() {
        System.clearProperty(ExtensionPropertyNames.SPI_ATTESTATION_MODE);
        TracePipelineSpiAttestation.resetEvidenceMarkerForTests();
    }

    @Test
    void generatedPinRejectsAttackerPrefixAndKeepsReviewedNames() {
        // given
        Set<String> names = PinnedUpstreamAcpProviders.NAMES;

        // when
        boolean attackerAllowed = TracePipelineSpiAttestation.isAllowlistedUpstreamProviderName(
                "attacker.io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer");

        // then
        assertThat(names).isNotEmpty();
        assertThat(names).doesNotContain(
                "attacker.io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer");
        assertThat(attackerAllowed).isFalse();
        assertThat(PlatformOtelAcpPinHashes.sha256Hex(names))
                .isEqualTo(PinnedUpstreamAcpProviders.PIN_SHA256);
        assertThat(names).hasSize(PinnedUpstreamAcpProviders.PIN_COUNT);
    }

    @Test
    void generatedPinEqualsIndependentExtractFromResolvedJar() throws Exception {
        // given
        Path agentJar = Path.of(requiredProperty("platformTracing.upstreamAgentJar"));

        // when
        List<String> extracted = IndependentUpstreamAcpJarExtractor.extractSortedNames(agentJar);

        // then
        assertThat(extracted).isEqualTo(PinnedUpstreamAcpProviders.NAMES.stream().sorted().toList());
        assertThat(PlatformOtelAcpPinHashes.sha256Hex(extracted))
                .isEqualTo(PinnedUpstreamAcpProviders.PIN_SHA256);
        assertThat(IndependentUpstreamAcpJarExtractor.sha256Hex(Files.readAllBytes(agentJar)))
                .isEqualTo(PinnedUpstreamAcpProviders.UPSTREAM_AGENT_SHA256);
    }

    @Test
    void platformProviderAlonePasses() {
        TracePipelineSpiAttestation.attest(PLATFORM_ORDER, BootstrapAutoConfigurationCustomizerProvider.class);
    }

    @Test
    void foreignAcpProviderLowerOrderFails(@TempDir Path overlayDir) throws IOException {
        assertAttestationFails(withOverlayAcpProvider(overlayDir, LowerOrderStaticProvider.class));
    }

    @Test
    void foreignAcpProviderSameOrderFails(@TempDir Path overlayDir) throws IOException {
        assertAttestationFails(withOverlayAcpProvider(overlayDir, SameOrderProvider.class));
    }

    @Test
    void foreignAcpProviderHigherOrderFails(@TempDir Path overlayDir) throws IOException {
        assertAttestationFails(withOverlayAcpProvider(overlayDir, HigherOrderProvider.class));
    }

    @Test
    void lowerOrderDelayedAcpMutationFails(@TempDir Path overlayDir) throws IOException {
        // The canonical bypass: order()==0 runs customize() before the platform provider, so an
        // order-only check would treat it as "already executed, therefore safe". It only registers
        // an exporter customizer LATER, from a properties-customizer callback executed during
        // getConfig() — after the platform boundary is already registered. Presence on the
        // classloader is rejected outright, independent of order() and of what customize() does.
        ClassLoader overlay = withOverlayAcpProvider(overlayDir, LowerOrderDelayedMutationProvider.class);
        LowerOrderDelayedMutationProvider.RAW_EXPORTER_ACTIVATED.set(false);

        assertAttestationFails(overlay);

        assertThat(LowerOrderDelayedMutationProvider.RAW_EXPORTER_ACTIVATED)
                .as("attestation must fail before OTel getConfig()/configureSdk() could ever invoke "
                        + "this provider's exporter customizer")
                .isFalse();
    }

    @Test
    void unknownProviderConstructorIsNotInvokedByAttestation(@TempDir Path overlayDir) throws IOException {
        // Discovery must reject a foreign provider by FQN alone (ServiceLoader.Provider::type),
        // never calling ::get() and therefore never running the foreign provider's constructor —
        // constructor side effects (e.g. process-global registration) must not happen just because
        // an unapproved provider is present on the classloader.
        ClassLoader overlay = withOverlayAcpProvider(overlayDir, ConstructorSideEffectProvider.class);
        ConstructorSideEffectProvider.CONSTRUCTED.set(false);

        assertAttestationFails(overlay);

        assertThat(ConstructorSideEffectProvider.CONSTRUCTED)
                .as("closed-allowlist rejection is based on FQN only; the foreign provider's "
                        + "constructor must never run")
                .isFalse();
    }

    @Test
    void missingPlatformProviderFails() throws IOException {
        String acpServiceName = "META-INF/services/" + AutoConfigurationCustomizerProvider.class.getName();
        ClassLoader hidingRealPlatformRegistration = new ServiceHidingClassLoader(
                TracePipelineSpiAttestationTest.class.getClassLoader(), acpServiceName);

        assertThatThrownBy(() -> TracePipelineSpiAttestation.attest(
                PLATFORM_ORDER, BootstrapAutoConfigurationCustomizerProvider.class, hidingRealPlatformRegistration))
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }

    @Test
    void platformProviderWrongOrderFails() {
        int wrongExpectedOrder = PLATFORM_ORDER + 1;
        assertThatThrownBy(() -> TracePipelineSpiAttestation.attest(
                wrongExpectedOrder, BootstrapAutoConfigurationCustomizerProvider.class))
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }

    @Test
    void legacyTracerProviderConfigurerDescriptorFailsWithoutLoadingProvider(@TempDir Path overlayDir)
            throws IOException {
        ClassLoader overlay = withOverlayConfigurer(overlayDir, LegacyConfigurerDescriptorProbe.class);
        LegacyConfigurerDescriptorProbe.CONSTRUCTED.set(false);

        assertAttestationFails(overlay);

        assertThat(LegacyConfigurerDescriptorProbe.CONSTRUCTED)
                .as("attestation must reject the legacy service descriptor without loading its provider")
                .isFalse();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }
        return value;
    }

    private static void assertAttestationFails(ClassLoader overlay) {
        assertThatThrownBy(() -> TracePipelineSpiAttestation.attest(
                PLATFORM_ORDER, BootstrapAutoConfigurationCustomizerProvider.class, overlay))
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }

    static ClassLoader withOverlayAcpProvider(Path overlayDir, Class<?> providerType) throws IOException {
        return withOverlayProvider(overlayDir, AutoConfigurationCustomizerProvider.class, providerType);
    }

    static ClassLoader withOverlayConfigurer(Path overlayDir, Class<?> configurerType) throws IOException {
        return withOverlayProvider(
                overlayDir, TracePipelineSpiAttestation.LEGACY_TRACER_PROVIDER_CONFIGURER_SPI, configurerType);
    }

    private static ClassLoader withOverlayProvider(Path overlayDir, Class<?> spiType, Class<?> providerType)
            throws IOException {
        return withOverlayProvider(overlayDir, spiType.getName(), providerType);
    }

    private static ClassLoader withOverlayProvider(Path overlayDir, String spiName, Class<?> providerType)
            throws IOException {
        Path servicesDir = overlayDir.resolve("META-INF").resolve("services");
        Files.createDirectories(servicesDir);
        Files.writeString(
                servicesDir.resolve(spiName),
                providerType.getName(),
                StandardCharsets.UTF_8);

        return new URLClassLoader(
                new URL[] {overlayDir.toUri().toURL()},
                TracePipelineSpiAttestationTest.class.getClassLoader());
    }

    /** Hides a single {@code META-INF/services} resource, simulating its total absence. */
    private static final class ServiceHidingClassLoader extends ClassLoader {
        private final String hiddenResourceName;

        ServiceHidingClassLoader(ClassLoader parent, String hiddenResourceName) {
            super(parent);
            this.hiddenResourceName = hiddenResourceName;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (hiddenResourceName.equals(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getResources(name);
        }
    }

    public static final class LowerOrderStaticProvider implements AutoConfigurationCustomizerProvider {
        @Override
        public int order() {
            return -1;
        }

        @Override
        public void customize(AutoConfigurationCustomizer customizer) {
            // не важно: должен быть отклонён на этапе classloader-visible inventory
        }
    }

    public static final class SameOrderProvider implements AutoConfigurationCustomizerProvider {
        @Override
        public int order() {
            return PLATFORM_ORDER;
        }

        @Override
        public void customize(AutoConfigurationCustomizer customizer) {
        }
    }

    public static final class HigherOrderProvider implements AutoConfigurationCustomizerProvider {
        @Override
        public int order() {
            return PLATFORM_ORDER + 1;
        }

        @Override
        public void customize(AutoConfigurationCustomizer customizer) {
        }
    }

    public static final class LowerOrderDelayedMutationProvider implements AutoConfigurationCustomizerProvider {

        static final AtomicBoolean RAW_EXPORTER_ACTIVATED = new AtomicBoolean();

        @Override
        public int order() {
            return 0;
        }

        @Override
        public void customize(AutoConfigurationCustomizer customizer) {
            customizer.addPropertiesCustomizer(config -> {
                customizer.addSpanExporterCustomizer((protectedExporter, ignoredConfig) -> {
                    RAW_EXPORTER_ACTIVATED.set(true);
                    return protectedExporter;
                });
                return Map.of();
            });
        }
    }

    public static final class ConstructorSideEffectProvider implements AutoConfigurationCustomizerProvider {

        static final AtomicBoolean CONSTRUCTED = new AtomicBoolean();

        public ConstructorSideEffectProvider() {
            CONSTRUCTED.set(true);
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public void customize(AutoConfigurationCustomizer customizer) {
        }
    }

    public static final class LegacyConfigurerDescriptorProbe {

        static final AtomicBoolean CONSTRUCTED = new AtomicBoolean();

        public LegacyConfigurerDescriptorProbe() {
            CONSTRUCTED.set(true);
        }
    }
}
