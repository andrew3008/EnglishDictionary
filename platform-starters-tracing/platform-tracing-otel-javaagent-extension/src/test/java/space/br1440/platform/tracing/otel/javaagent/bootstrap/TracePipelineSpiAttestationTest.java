package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

class TracePipelineSpiAttestationTest {

    private static final int PLATFORM_ORDER = 100;

    @Test
    void allowlisted_names_reject_attacker_prefix_and_keep_reviewed_names() {
        // given
        Set<String> names = AllowedUpstreamAcpProviders.NAMES;

        // when
        boolean attackerAllowed = TracePipelineSpiAttestation.isAllowlistedOtelJavaAgentProviderName(
                "attacker.io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer");

        // then
        assertThat(names).isNotEmpty();
        assertThat(names).doesNotContain(
                "attacker.io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer");
        assertThat(attackerAllowed).isFalse();
    }

    @Test
    void platformProviderAlonePasses() {
        // when/then
        TracePipelineSpiAttestation.attest(PLATFORM_ORDER, BootstrapAutoConfigurationCustomizerProvider.class);
    }

    @Test
    void foreignAcpProviderLowerOrderFails(@TempDir Path overlayDir) throws IOException {
        // given
        ClassLoader overlay = withOverlayAcpProvider(overlayDir, LowerOrderStaticProvider.class);

        // when/then
        assertAttestationFails(overlay);
    }

    @Test
    void foreignAcpProviderSameOrderFails(@TempDir Path overlayDir) throws IOException {
        // given
        ClassLoader overlay = withOverlayAcpProvider(overlayDir, SameOrderProvider.class);

        // when/then
        assertAttestationFails(overlay);
    }

    @Test
    void foreignAcpProviderHigherOrderFails(@TempDir Path overlayDir) throws IOException {
        // given
        ClassLoader overlay = withOverlayAcpProvider(overlayDir, HigherOrderProvider.class);

        // when/then
        assertAttestationFails(overlay);
    }

    @Test
    void lowerOrderDelayedAcpMutationFails(@TempDir Path overlayDir) throws IOException {
        // given
        ClassLoader overlay = withOverlayAcpProvider(overlayDir, LowerOrderDelayedMutationProvider.class);
        LowerOrderDelayedMutationProvider.RAW_EXPORTER_ACTIVATED.set(false);

        // when/then
        assertAttestationFails(overlay);

        // then
        assertThat(LowerOrderDelayedMutationProvider.RAW_EXPORTER_ACTIVATED)
                .as("attestation must fail before OTel getConfig()/configureSdk() could ever invoke "
                        + "this provider's exporter customizer")
                .isFalse();
    }

    @Test
    void unknownProviderConstructorIsNotInvokedByAttestation(@TempDir Path overlayDir) throws IOException {
        // given
        ClassLoader overlay = withOverlayAcpProvider(overlayDir, ConstructorSideEffectProvider.class);
        ConstructorSideEffectProvider.CONSTRUCTED.set(false);

        // when/then
        assertAttestationFails(overlay);

        // then
        assertThat(ConstructorSideEffectProvider.CONSTRUCTED)
                .as("closed-allowlist rejection is based on FQN only; the foreign provider's "
                        + "constructor must never run")
                .isFalse();
    }

    @Test
    void missingPlatformProviderFails() {
        // given
        String acpServiceName = "META-INF/services/" + AutoConfigurationCustomizerProvider.class.getName();
        ClassLoader hidingRealPlatformRegistration = new ServiceHidingClassLoader(
                TracePipelineSpiAttestationTest.class.getClassLoader(), acpServiceName);

        // when/then
        assertThatThrownBy(() -> TracePipelineSpiAttestation.attest(
                PLATFORM_ORDER, BootstrapAutoConfigurationCustomizerProvider.class, hidingRealPlatformRegistration))
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }

    @Test
    void platformProviderWrongOrderFails() {
        // given
        int wrongExpectedOrder = PLATFORM_ORDER + 1;

        // when/then
        assertThatThrownBy(() -> TracePipelineSpiAttestation.attest(
                wrongExpectedOrder, BootstrapAutoConfigurationCustomizerProvider.class))
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }

    @Test
    void legacyTracerProviderConfigurerDescriptorFailsWithoutLoadingProvider(@TempDir Path overlayDir)
            throws IOException {
        // given
        ClassLoader overlay = withOverlayConfigurer(overlayDir, LegacyConfigurerDescriptorProbe.class);
        LegacyConfigurerDescriptorProbe.CONSTRUCTED.set(false);

        // when/then
        assertAttestationFails(overlay);

        // then
        assertThat(LegacyConfigurerDescriptorProbe.CONSTRUCTED)
                .as("attestation must reject the legacy service descriptor without loading its provider")
                .isFalse();
    }

    private static void assertAttestationFails(ClassLoader overlay) {
        assertThatThrownBy(() -> TracePipelineSpiAttestation.attest(
                PLATFORM_ORDER, BootstrapAutoConfigurationCustomizerProvider.class, overlay))
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }

    static ClassLoader withOverlayAcpProvider(Path overlayDir, Class<?> providerType) throws IOException {
        return withOverlayProvider(overlayDir, AutoConfigurationCustomizerProvider.class.getName(), providerType);
    }

    @SuppressWarnings("SameParameterValue")
    static ClassLoader withOverlayConfigurer(Path overlayDir, Class<?> configurerType) throws IOException {
        return withOverlayProvider(overlayDir, TracePipelineSpiAttestation.LEGACY_TRACER_PROVIDER_CONFIGURER_SPI, configurerType);
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
