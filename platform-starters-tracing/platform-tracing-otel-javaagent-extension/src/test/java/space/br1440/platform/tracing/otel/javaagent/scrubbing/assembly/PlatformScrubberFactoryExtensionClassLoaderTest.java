package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

class PlatformScrubberFactoryExtensionClassLoaderTest {

    private static final String PROBE_CLASS_NAME = "extensionprobe.ProbeRule";
    private static final String PROBE_CLASS_RESOURCE = "extensionprobe/ProbeRule.class";
    private static final String PROBE_HELPER_CLASS_RESOURCE = "extensionprobe/LazyDecision.class";
    private static final String SERVICE_ENTRY = "META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule";
    private static final String PROBE_SOURCE = """
            package extensionprobe;

            import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
            import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

            public final class ProbeRule implements SpanAttributeScrubbingRule {
                @Override
                public String name() {
                    return "extension-probe";
                }

                @Override
                public int priority() {
                    return 1000;
                }

                @Override
                public ScrubbingDecision evaluate(String key, Object value) {
                    try {
                        Class<?> helper = Class.forName(
                                "extensionprobe.LazyDecision",
                                true,
                                getClass().getClassLoader()
                        );
                        return (ScrubbingDecision) helper.getMethod("decision").invoke(null);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("Failed to load the lazy scrubbing helper", failure);
                    }
                }
            }
            """;
    private static final String PROBE_HELPER_SOURCE = """
            package extensionprobe;

            import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

            public final class LazyDecision {
                private LazyDecision() {
                }

                public static ScrubbingDecision decision() {
                    return ScrubbingDecision.mask("probe");
                }
            }
            """;

    @Test
    void keepsExtensionClassLoaderAvailableForLazyRuleDependencies(@TempDir Path directory) throws IOException {
        // given
        Path classes = directory.resolve("classes");
        compileProbe(directory.resolve("src"), classes);
        Path jar = directory.resolve("rules.jar");
        writeJar(jar, Map.of(
                PROBE_CLASS_RESOURCE, Files.readAllBytes(classes.resolve(PROBE_CLASS_RESOURCE)),
                PROBE_HELPER_CLASS_RESOURCE, Files.readAllBytes(classes.resolve(PROBE_HELPER_CLASS_RESOURCE)),
                SERVICE_ENTRY, (PROBE_CLASS_NAME + "\n").getBytes(StandardCharsets.UTF_8)
        ));

        // when
        ScrubbingRuntime runtime = new PlatformScrubberFactory()
                .buildRuntime(extensionConfig(jar, "WARNING"));

        // then
        ExecutableRule probe = runtime.attributeExecutableRules().stream()
                .filter(rule -> PROBE_CLASS_NAME.equals(rule.getRuleClass().getName()))
                .findFirst()
                .orElseThrow();
        ScrubbingDecision decision =
                MergeEngine.evaluateDetailed(List.of(probe), "subject", "value").decision();
        assertThat(decision.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(decision.reason()).isEqualTo("probe");

        ClassLoader extensionLoader = probe.getRuleClass().getClassLoader();
        assertThat(extensionLoader)
                .isInstanceOf(URLClassLoader.class)
                .isNotSameAs(PlatformScrubberFactory.class.getClassLoader());
        assertThat(extensionLoader.getResource(PROBE_HELPER_CLASS_RESOURCE)).isNotNull();

        // when
        runtime.close();

        // then
        assertThat(extensionLoader.getResource(PROBE_HELPER_CLASS_RESOURCE)).isNull();
        assertJarReleased(jar);
    }

    @Test
    void closesExtensionClassLoaderWhenValidationFails(@TempDir Path directory) throws IOException {
        // given
        Path jar = directory.resolve("broken.jar");
        writeJar(jar, Map.of(
                SERVICE_ENTRY, "com.example.DoesNotExist\n".getBytes(StandardCharsets.UTF_8)
        ));

        // when/then
        assertThatThrownBy(() -> {
            try (ScrubbingRuntime runtime = new PlatformScrubberFactory()
                    .buildRuntime(extensionConfig(jar, "ERROR"))) {
                throw new AssertionError("buildRuntime returned a runtime: " + runtime.enabled());
            }
        }).isInstanceOfSatisfying(SanitizerBootstrapException.class, failure -> {
            assertThat(failure.code()).isEqualTo(SanitizerBootstrapException.RULE_LOADING_FAILED);
            assertThat(failure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SERVICE_CONFIGURATION_ERROR");
        });
        assertJarReleased(jar);
    }

    private static void compileProbe(Path sourceDirectory, Path classesDirectory) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Extension class-loader test requires a JDK system compiler");
        }

        Path packageDirectory = sourceDirectory.resolve("extensionprobe");
        Files.createDirectories(packageDirectory);
        Files.createDirectories(classesDirectory);
        Path source = packageDirectory.resolve("ProbeRule.java");
        Path helperSource = packageDirectory.resolve("LazyDecision.java");
        Files.writeString(source, PROBE_SOURCE);
        Files.writeString(helperSource, PROBE_HELPER_SOURCE);

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(List.of(source, helperSource));
            StringWriter diagnostics = new StringWriter();
            Boolean compiled = compiler.getTask(
                    diagnostics,
                    fileManager,
                    null,
                    List.of(
                            "--class-path", System.getProperty("java.class.path"),
                            "-encoding", "UTF-8",
                            "-d", classesDirectory.toString()
                    ),
                    null,
                    units
            ).call();
            if (!Boolean.TRUE.equals(compiled)) {
                throw new IllegalStateException("Probe rule compilation failed: " + diagnostics);
            }
        }
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jarOutput.putNextEntry(new JarEntry(entry.getKey()));
                jarOutput.write(entry.getValue());
                jarOutput.closeEntry();
            }
        }
    }

    private static ExtensionConfig extensionConfig(Path jar, String validationMode) {
        return new ExtensionConfig(new ConfigProperties() {
            @Override
            public String getString(String name) {
                if (ExtensionPropertyNames.SCRUBBING_RULES_EXTENSIONS.equals(name)) {
                    return jar.toString();
                }
                if (ExtensionPropertyNames.SCRUBBING_RULES_VALIDATION_MODE.equals(name)) {
                    return validationMode;
                }
                return null;
            }

            @Override
            public Boolean getBoolean(String name) {
                return null;
            }

            @Override
            public Integer getInt(String name) {
                return null;
            }

            @Override
            public Long getLong(String name) {
                return null;
            }

            @Override
            public Double getDouble(String name) {
                return null;
            }

            @Override
            public Duration getDuration(String name) {
                return null;
            }

            @Override
            public List<String> getList(String name) {
                return null;
            }

            @Override
            public Map<String, String> getMap(String name) {
                return null;
            }
        });
    }

    private static void assertJarReleased(Path jar) throws IOException {
        Path descriptors = Path.of("/proc/self/fd");
        if (Files.isDirectory(descriptors)) {
            try (Stream<Path> links = Files.list(descriptors)) {
                assertThat(links.filter(link -> sameFile(link, jar)).toList()).isEmpty();
            }
            return;
        }

        Files.delete(jar);
    }

    private static boolean sameFile(Path link, Path jar) {
        try {
            return Files.isSameFile(Files.readSymbolicLink(link), jar);
        } catch (IOException failure) {
            return false;
        }
    }
}
