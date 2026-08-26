package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class JmxSecurityArchitectureGateTest {

    private static final String EXTENSION_MAIN_SOURCE_PREFIX =
            "platform-starters-tracing/platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/"
                    + "platform/tracing/otel/javaagent/";
    private static final String SECURITY_PACKAGE_PATH =
            EXTENSION_MAIN_SOURCE_PREFIX + "jmx/security/";
    private static final String REMOTE_JMX_EXPOSURE_POLICY =
            SECURITY_PACKAGE_PATH + "RemoteJmxExposurePolicy.java";
    private static final String DOMAIN_MBEAN_REGISTRAR =
            EXTENSION_MAIN_SOURCE_PREFIX + "jmx/registration/DomainMBeanRegistrar.java";
    private static final String BOOTSTRAP_COORDINATOR =
            EXTENSION_MAIN_SOURCE_PREFIX + "bootstrap/BootstrapCoordinator.java";

    private static final Set<String> PRIVATE_SERVER_FACTORY_ALLOWLIST = Set.of();

    private static final String PACKAGED_CERTIFICATION_RUNNER =
            "platform-starters-tracing/platform-tracing-e2e-tests/src/main/java/space/br1440/platform/"
                    + "tracing/e2e/PackagedCertificationRunner.java";
    private static final String PACKAGED_CERTIFICATION_APPLICATION =
            "platform-starters-tracing/platform-tracing-e2e-tests/src/main/java/space/br1440/platform/"
                    + "tracing/e2e/PackagedTracingCertificationApplication.java";
    private static final String PACKAGED_JMX_CONNECTOR_PREMAIN_AGENT =
            "platform-starters-tracing/platform-tracing-e2e-tests/src/jmxFixtureAgent/java/space/br1440/"
                    + "platform/tracing/e2e/jmx/PackagedJmxConnectorPremainAgent.java";

    private static final Set<String> NONPUBLISHED_CERTIFICATION_CONNECTOR_HIT_ALLOWLIST = Set.of(
            PACKAGED_CERTIFICATION_RUNNER + ": JMXServiceURL",
            PACKAGED_JMX_CONNECTOR_PREMAIN_AGENT + ": JMXConnectorServer",
            PACKAGED_JMX_CONNECTOR_PREMAIN_AGENT + ": JMXConnectorServerFactory",
            PACKAGED_JMX_CONNECTOR_PREMAIN_AGENT + ": JMXServiceURL",
            PACKAGED_JMX_CONNECTOR_PREMAIN_AGENT + ": RMIConnectorServer");

    private static final Set<String> NONPUBLISHED_CERTIFICATION_LOADER_HACK_HIT_ALLOWLIST = Set.of(
            PACKAGED_CERTIFICATION_RUNNER + ": Class.forName(",
            PACKAGED_CERTIFICATION_APPLICATION + ": Class.forName(");

    private static final Set<String> LOADER_HACK_EXACT_ALLOWLIST = Set.of(
            "platform-starters-tracing/platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/"
                    + "platform/tracing/otel/javaagent/resource/ClasspathResourceLoader.java");

    @Test
    void connector_server_apis_are_confined_to_security_package() throws IOException {
        // given
        List<String> connectorTokens = List.of(
                "JMXConnectorServer",
                "JMXConnectorServerFactory",
                "JMXServiceURL",
                "RMIConnectorServer",
                "LocateRegistry",
                "UnicastRemoteObject");

        // when
        List<String> unexpected = findHits(
                connectorTokens,
                path -> !path.contains(SECURITY_PACKAGE_PATH)).stream()
                .filter(hit -> !NONPUBLISHED_CERTIFICATION_CONNECTOR_HIT_ALLOWLIST.contains(hit))
                .toList();

        // then
        assertThat(unexpected).isEmpty();
    }

    @Test
    void private_mbean_server_factory_usage_is_forbidden() throws IOException {
        // given
        List<String> factoryTokens = List.of(
                "MBeanServerFactory.createMBeanServer",
                "MBeanServerFactory.findMBeanServer");

        // when
        List<String> unexpected = findHits(
                factoryTokens,
                path -> !PRIVATE_SERVER_FACTORY_ALLOWLIST.contains(path));

        // then
        assertThat(unexpected).isEmpty();
    }

    @Test
    void tracing_production_code_does_not_add_loader_hacks() throws IOException {
        // given
        List<String> loaderHackTokens = List.of(
                "getContextClassLoader(",
                "setContextClassLoader(",
                "Class.forName(",
                ".loadClass(",
                "Proxy.newProxyInstance(",
                "MethodHandles.privateLookupIn(",
                "appendToBootstrapClassLoaderSearch(",
                "appendToSystemClassLoaderSearch(",
                "redefineModule(");

        // when
        List<String> unexpected = findHits(loaderHackTokens, path -> true).stream()
                .filter(hit -> !isLoaderHackAllowlisted(hit))
                .toList();

        // then
        assertThat(unexpected).isEmpty();
    }

    @Test
    void mutation_registration_permit_has_single_production_issuer() throws IOException {
        // given
        String constructionToken = "new MutationRegistrationPermit(";

        // when
        List<String> hits = findHits(
                List.of(constructionToken),
                path -> path.startsWith(EXTENSION_MAIN_SOURCE_PREFIX));

        // then
        assertThat(hits).containsExactly(REMOTE_JMX_EXPOSURE_POLICY + ": " + constructionToken);
    }

    @Test
    void runtime_registration_has_single_production_caller() throws IOException {
        // given
        String registrationToken = "registerRuntimeControls(";

        // when
        List<String> hits = findHits(
                List.of(registrationToken),
                path -> path.startsWith(EXTENSION_MAIN_SOURCE_PREFIX));

        // then
        assertThat(hits).containsExactlyInAnyOrder(
                DOMAIN_MBEAN_REGISTRAR + ": " + registrationToken,
                BOOTSTRAP_COORDINATOR + ": " + registrationToken);
    }

    @Test
    void legacy_unguarded_registration_entry_point_is_absent() throws IOException {
        // given
        String legacyEntryPoint = "tryRegisterMBeans(";

        // when
        List<String> hits = findHits(
                List.of(legacyEntryPoint),
                path -> path.startsWith(EXTENSION_MAIN_SOURCE_PREFIX));

        // then
        assertThat(hits).isEmpty();
    }

    @Test
    void bulk_deregistration_bypass_is_absent() throws IOException {
        // given
        String bypassEntryPoint = "unregisterAllMBeans(";

        // when
        List<String> hits = findHits(
                List.of(bypassEntryPoint),
                path -> path.startsWith(EXTENSION_MAIN_SOURCE_PREFIX));

        // then
        assertThat(hits).isEmpty();
    }

    @Test
    void spring_plane_does_not_use_standard_mbean_proxies() throws IOException {
        // given
        String springSourcePrefix =
                "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/";

        // when
        List<String> hits = findHits(
                List.of("JMX.newMBeanProxy(", "MBeanServerInvocationHandler"),
                path -> path.startsWith(springSourcePrefix));

        // then
        assertThat(hits).isEmpty();
    }

    @Test
    void spring_plane_javaagent_reference_is_limited_to_marker_detector() throws IOException {
        // given
        String springSourcePrefix =
                "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/";
        String markerDetector = springSourcePrefix
                + "space/br1440/platform/tracing/autoconfigure/agent/OtelJavaAgentMarkerDetector.java";

        // when
        List<String> unexpected = findHits(
                List.of("io.opentelemetry.javaagent."),
                path -> path.startsWith(springSourcePrefix) && !path.equals(markerDetector));

        // then
        assertThat(unexpected).isEmpty();
    }

    private static boolean isLoaderHackAllowlisted(String hit) {
        if (NONPUBLISHED_CERTIFICATION_LOADER_HACK_HIT_ALLOWLIST.contains(hit)) {
            return true;
        }
        return LOADER_HACK_EXACT_ALLOWLIST.stream()
                .anyMatch(path -> hit.startsWith(path + ": "));
    }

    private static List<String> findHits(List<String> tokens, Predicate<String> includePath) throws IOException {
        Path repositoryRoot = repositoryRoot();
        Path tracingRoot = repositoryRoot.resolve("platform-starters-tracing");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(tracingRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> isScannedSource(repositoryRoot, path))
                    .forEach(path -> collectHits(repositoryRoot, path, tokens, includePath, hits));
        }
        return hits;
    }

    private static boolean isScannedSource(Path repositoryRoot, Path source) {
        String relative = normalized(repositoryRoot.relativize(source));
        return relative.contains("/src/main/java/")
                || relative.equals(PACKAGED_JMX_CONNECTOR_PREMAIN_AGENT);
    }

    private static void collectHits(
            Path repositoryRoot,
            Path source,
            List<String> tokens,
            Predicate<String> includePath,
            List<String> hits) {
        String relative = normalized(repositoryRoot.relativize(source));
        if (!includePath.test(relative)) {
            return;
        }
        String content;
        try {
            content = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Java source " + relative, exception);
        }
        for (String token : tokens) {
            if (content.contains(token)) {
                hits.add(relative + ": " + token);
            }
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from user.dir");
        }
        return current;
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }
}
