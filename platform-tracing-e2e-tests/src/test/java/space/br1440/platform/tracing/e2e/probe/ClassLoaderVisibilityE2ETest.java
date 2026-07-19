package space.br1440.platform.tracing.e2e.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-верификация classloader-видимости под реальным OpenTelemetry Java Agent.
 *
 * <ul>
 *   <li><b>F1</b>: нативный {@link java.util.ServiceLoader} в ExtensionClassLoader агента не видит
 *       sibling custom-rules JAR (ADR-classloader-visibility-spike-finding, F1).</li>
 *   <li><b>URLClassLoader mechanism smoke</b> (optional): probe-side упрощённый
 *       {@code URLClassLoader + ServiceLoader} по пути {@code platform.tracing.scrubbing.rules.extensions}.
 *       Не доказывает production {@code ExtensionRuleLoader} semantics.</li>
 * </ul>
 *
 * <p>F1 доказывается probe'ом {@code ClassLoaderVisibilityTestProbe}, загруженным как OTel
 * extension через {@code otel.javaagent.extensions} — не из application {@code main()}.</p>
 *
 * <p>Production F3 (custom rule loading/application) — {@code CustomRuleSmokeE2ETest}.</p>
 */
@DisabledIfSystemProperty(named = "skipE2e", matches = "true")
class ClassLoaderVisibilityE2ETest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);
    private static final String CL_PREFIX = "CL_VISIBILITY:";

    @Test
    void classloader_visibility_verified_by_extension_probe() throws Exception {
        String otelAgentJar = System.getProperty("otel.javaagent.jar");
        String extensionJar = System.getProperty("smoke.otel.extension.jar");
        String customRuleJar = System.getProperty("smoke.custom.rule.jar");
        String probeJar      = System.getProperty("smoke.test.classloader.probe.extension.jar");

        assertThat(otelAgentJar).as("otel.javaagent.jar").isNotBlank();
        assertThat(extensionJar).as("smoke.otel.extension.jar").isNotBlank();
        assertThat(customRuleJar).as("smoke.custom.rule.jar").isNotBlank();
        assertThat(probeJar).as("smoke.test.classloader.probe.extension.jar").isNotBlank();
        assertThat(new File(otelAgentJar)).exists().isFile();
        assertThat(new File(extensionJar)).exists().isFile();
        assertThat(new File(customRuleJar)).exists().isFile();
        assertThat(new File(probeJar)).exists().isFile();

        // Оба JAR расширений помещаем в один temp-каталог, передаваемый через otel.javaagent.extensions.
        // OTel Agent 2.28.x создаёт отдельный ExtensionClassLoader для каждого JAR в директории;
        // probe JAR встраивает platform-tracing-api, чтобы SpanAttributeScrubbingRule был доступен в его CL.
        Path extDir = Files.createTempDirectory("cl-visibility-ext");
        Files.copy(Path.of(extensionJar), extDir.resolve("extension.jar"));
        Files.copy(Path.of(probeJar),      extDir.resolve("cl-probe.jar"));
        String platformExtension    = extDir.toAbsolutePath().toString();
        String customRulesProperty  = Path.of(customRuleJar).toAbsolutePath().toString();

        String output = runProbeProcess(platformExtension, customRulesProperty);
        System.out.println("=== ClassLoader Visibility E2E output ===");
        System.out.println(output);

        // --- F1: probe-маркеры присутствуют (доказательство: probe выполнился в ExtensionCL) ---
        assertThat(output)
                .as("Probe должен вывести CL_VISIBILITY:BEGIN — ExtensionClassLoader загрузил probe")
                .contains(CL_PREFIX + "BEGIN");
        assertThat(output)
                .as("Probe должен вывести CL_VISIBILITY:END")
                .contains(CL_PREFIX + "END");
        assertThat(output)
                .as("extensionProbeLoaded=true — SPI entrypoint сработал")
                .contains(CL_PREFIX + "extensionProbeLoaded=true");
        assertThat(output)
                .as("probeClassLoader должен быть ExtensionClassLoader, а не AppClassLoader")
                .contains(CL_PREFIX + "probeClassLoader=io.opentelemetry.javaagent.tooling.ExtensionClassLoader");
        assertThat(output)
                .contains(CL_PREFIX + "extensionApiClassLoader=io.opentelemetry.javaagent.tooling.ExtensionClassLoader")
                .contains(CL_PREFIX + "applicationLauncherVisibleFromExtension=false")
                .contains(CL_PREFIX + "extensionProbeVisibleFromApplication=false")
                .contains(CL_PREFIX + "applicationAgentMarkerPresent=true")
                .contains(CL_PREFIX + "applicationCurrentSpanValid=true");

        String extensionApiLoader = markerValue(output, "extensionApiClassLoader");
        String applicationApiLoader = markerValue(output, "applicationApiClassLoader");
        assertThat(applicationApiLoader)
                .as("API application plane должен принадлежать application ClassLoader")
                .doesNotContain("ExtensionClassLoader");
        assertThat(applicationApiLoader)
                .as("Application и agent extension должны иметь разные class identities API")
                .isNotEqualTo(extensionApiLoader);
        assertThat(markerValue(output, "applicationCurrentTraceId"))
                .as("Current context должен быть видим application plane через OTel Context")
                .matches("[0-9a-f]{32}")
                .doesNotMatch("0{32}");

        // --- F1: все варианты ServiceLoader не видят custom-rule JAR ---
        List<Map<String, String>> variants = parseCLVariants(output);
        assertThat(variants)
                .as("Probe должен вывести варианты ServiceLoader")
                .isNotEmpty();

        Map<String, Map<String, String>> byVariant = new LinkedHashMap<>();
        for (Map<String, String> variant : variants) {
            byVariant.put(variant.get("variant"), variant);
        }
        assertThat(byVariant)
                .as("Ожидаемые варианты: default, tccl, factory, api")
                .containsKeys("default", "tccl", "factory", "api");

        boolean nativeVisible = byVariant.values().stream()
                .anyMatch(v -> "true".equals(v.get("targetFound")));
        assertThat(nativeVisible)
                .as("F1 ADR: нативный ServiceLoader в ExtensionClassLoader не должен видеть "
                        + "custom-e2e-rule из JAR, переданного через "
                        + "platform.tracing.scrubbing.rules.extensions")
                .isFalse();

        // --- URLClassLoader mechanism smoke (optional; not production ExtensionRuleLoader proof) ---
        assertThat(output)
                .as("Mechanism smoke: custom-rules JAR передан через platform.tracing.scrubbing.rules.extensions")
                .contains(CL_PREFIX + "mechanismLoadingMode=PLATFORM_RULES_EXTENSIONS");
        assertThat(output)
                .as("Mechanism smoke: probe-side URLClassLoader нашёл одно кастомное правило "
                        + "(не эквивалент production ExtensionRuleLoader.load)")
                .contains(CL_PREFIX + "mechanismCustomRules=1");
    }

    private static String runProbeProcess(String extensionDir, String customRulesProperty)
            throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        String testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");
        String otelAgentJar         = System.getProperty("otel.javaagent.jar");

        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        command.add("-Dotel.javaagent.extensions=" + extensionDir.replace('\\', '/'));
        command.add("-Dplatform.tracing.scrubbing.rules.extensions=" + customRulesProperty.replace('\\', '/'));
        command.add("-Dotel.service.name=cl-visibility-e2e");
        command.add("-Dotel.traces.exporter=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dplatform.tracing.queue.overflow-policy=UPSTREAM");
        command.add("-Dotel.javaagent.logging=application");
        command.add("-javaagent:" + otelAgentJar);
        command.add("-cp");
        command.add(testRuntimeClasspath);
        command.add(ClassLoaderVisibilityE2ELauncher.class.getName());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (Exception ignored) {
            }
        }, "cl-visibility-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        reader.join(5_000L);

        assertThat(finished)
                .as("Probe JVM должна завершиться за %s. Output:\n%s", PROCESS_TIMEOUT, output)
                .isTrue();
        assertThat(process.exitValue())
                .as("Probe JVM exit code. Output:\n%s", output)
                .isZero();
        assertThat(output.toString())
                .as("Agent должен стартовать без ошибок")
                .doesNotContain("OpenTelemetry Javaagent failed to start");

        return output.toString();
    }

    /**
     * Парсит блоки {@code CL_VISIBILITY:variant=...} / {@code CL_VISIBILITY:variantEnd=...}.
     * Каждый Map содержит все key=value пары внутри блока варианта.
     */
    private static List<Map<String, String>> parseCLVariants(String output) {
        List<Map<String, String>> variants = new ArrayList<>();
        Map<String, String> current = null;

        for (String line : output.split("\\R")) {
            if (!line.startsWith(CL_PREFIX)) {
                continue;
            }
            String payload = line.substring(CL_PREFIX.length());

            if (payload.startsWith("variant=") && !payload.startsWith("variantEnd=")) {
                current = new LinkedHashMap<>();
            }
            if (current != null) {
                int eq = payload.indexOf('=');
                if (eq > 0) {
                    current.put(payload.substring(0, eq), payload.substring(eq + 1));
                }
            }
            if (payload.startsWith("variantEnd=") && current != null) {
                variants.add(current);
                current = null;
            }
        }
        return variants;
    }

    private static String markerValue(String output, String key) {
        String prefix = CL_PREFIX + key + "=";
        return output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Marker not found: " + key));
    }
}
