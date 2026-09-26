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
    private static final String PLATFORM_TRACING_MBEAN_REGISTRAR =
            EXTENSION_MAIN_SOURCE_PREFIX + "jmx/registration/PlatformTracingMBeanRegistrar.java";
    private static final String BOOTSTRAP_ACTIVATION =
            EXTENSION_MAIN_SOURCE_PREFIX + "bootstrap/BootstrapActivation.java";
    private static final String SPRING_SOURCE_PREFIX =
            "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java/";
    private static final String MARKER_DETECTOR = SPRING_SOURCE_PREFIX
            + "space/br1440/platform/tracing/autoconfigure/agent/OtelJavaAgentMarkerDetector.java";

    private static final List<String> CONNECTOR_TOKENS = List.of(
            "JMXConnectorServer",
            "JMXConnectorServerFactory",
            "JMXServiceURL",
            "RMIConnectorServer",
            "LocateRegistry",
            "UnicastRemoteObject");
    private static final List<String> FACTORY_TOKENS = List.of(
            "MBeanServerFactory.createMBeanServer",
            "MBeanServerFactory.findMBeanServer");
    private static final List<String> LOADER_HACK_TOKENS = List.of(
            "getContextClassLoader(",
            "setContextClassLoader(",
            "Class.forName(",
            ".loadClass(",
            "Proxy.newProxyInstance(",
            "MethodHandles.privateLookupIn(",
            "appendToBootstrapClassLoaderSearch(",
            "appendToSystemClassLoaderSearch(",
            "redefineModule(");
    private static final String REGISTRATION_TOKEN = "registerRuntimeControls(";
    private static final List<String> SPRING_PROXY_TOKENS = List.of(
            "JMX.newMBeanProxy(",
            "MBeanServerInvocationHandler");
    private static final String JAVAAGENT_TOKEN = "io.opentelemetry.javaagent.";

    private static final Set<String> LOADER_HACK_EXACT_ALLOWLIST = Set.of(
            "platform-starters-tracing/platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/"
                    + "platform/tracing/otel/javaagent/resource/BuildInfoReader.java");

    private static final SourceIndex INDEX = SourceIndex.scan();

    @Test
    void connector_server_apis_are_confined_to_security_package() {
        // given
        Predicate<String> outsideSecurityPackage = path -> !path.contains(SECURITY_PACKAGE_PATH);

        // when
        List<String> unexpected = INDEX.hits(CONNECTOR_TOKENS, outsideSecurityPackage);

        // then
        assertThat(unexpected).isEmpty();
    }

    @Test
    void private_mbean_server_factory_usage_is_forbidden() {
        // when
        List<String> unexpected = INDEX.hits(FACTORY_TOKENS, path -> true);

        // then
        assertThat(unexpected).isEmpty();
    }

    @Test
    void tracing_production_code_does_not_add_loader_hacks() {
        // when
        List<String> unexpected = INDEX.hits(LOADER_HACK_TOKENS, path -> true).stream()
                .filter(hit -> !isLoaderHackAllowlisted(hit))
                .toList();

        // then
        assertThat(unexpected).isEmpty();
    }

    @Test
    void runtime_registration_has_single_production_caller() {
        // when
        List<String> hits = INDEX.hits(
                List.of(REGISTRATION_TOKEN),
                path -> path.startsWith(EXTENSION_MAIN_SOURCE_PREFIX));

        // then
        assertThat(hits).containsExactlyInAnyOrder(
                PLATFORM_TRACING_MBEAN_REGISTRAR + ": " + REGISTRATION_TOKEN,
                BOOTSTRAP_ACTIVATION + ": " + REGISTRATION_TOKEN);
    }

    @Test
    void spring_plane_does_not_use_standard_mbean_proxies() {
        // when
        List<String> hits = INDEX.hits(
                SPRING_PROXY_TOKENS,
                path -> path.startsWith(SPRING_SOURCE_PREFIX));

        // then
        assertThat(hits).isEmpty();
    }

    @Test
    void spring_plane_javaagent_reference_is_limited_to_marker_detector() {
        // when
        List<String> unexpected = INDEX.hits(
                List.of(JAVAAGENT_TOKEN),
                path -> path.startsWith(SPRING_SOURCE_PREFIX) && !path.equals(MARKER_DETECTOR));

        // then
        assertThat(unexpected).isEmpty();
    }

    private static boolean isLoaderHackAllowlisted(String hit) {
        return LOADER_HACK_EXACT_ALLOWLIST.stream()
                .anyMatch(path -> hit.startsWith(path + ": "));
    }

    private record Hit(String path, String token) {

        private String format() {
            return path + ": " + token;
        }
    }

    private static final class SourceIndex {

        private final List<Hit> hits;

        private SourceIndex(List<Hit> hits) {
            this.hits = hits;
        }

        private static SourceIndex scan() {
            Path repositoryRoot = repositoryRoot();
            Path tracingRoot = repositoryRoot.resolve("platform-starters-tracing");
            List<String> tokens = scanTokens();
            List<Hit> hits = new ArrayList<>();
            try (Stream<Path> files = Files.walk(tracingRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> collectHits(repositoryRoot, path, tokens, hits));
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot scan tracing production sources", exception);
            }
            return new SourceIndex(List.copyOf(hits));
        }

        private List<String> hits(List<String> tokens, Predicate<String> includePath) {
            Set<String> selected = Set.copyOf(tokens);
            return hits.stream()
                    .filter(hit -> selected.contains(hit.token()))
                    .filter(hit -> includePath.test(hit.path()))
                    .map(Hit::format)
                    .toList();
        }

        private static List<String> scanTokens() {
            List<String> tokens = new ArrayList<>();
            tokens.addAll(CONNECTOR_TOKENS);
            tokens.addAll(FACTORY_TOKENS);
            tokens.addAll(LOADER_HACK_TOKENS);
            tokens.add(REGISTRATION_TOKEN);
            tokens.addAll(SPRING_PROXY_TOKENS);
            tokens.add(JAVAAGENT_TOKEN);
            return List.copyOf(tokens);
        }

        private static void collectHits(
                Path repositoryRoot,
                Path source,
                List<String> tokens,
                List<Hit> hits) {
            String relative = normalized(repositoryRoot.relativize(source));
            if (!relative.contains("/src/main/java/")) {
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
                    hits.add(new Hit(relative, token));
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
}
