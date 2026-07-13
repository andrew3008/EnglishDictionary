package space.br1440.platform.tracing.e2e.support;

import space.br1440.platform.tracing.e2e.smoke.ResourceIdentityAgentSmokeMain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запуск дочерней JVM с OTel Java Agent + {@code platform-tracing-otel-extension} и
 * {@link ResourceIdentityAgentSmokeMain} для проверки resource-идентичности (Фаза 9).
 * <p>
 * Идентичность задаётся через {@code -Dplatform.tracing.service.*} (а не {@code otel.service.name}),
 * чтобы проверить путь {@code PlatformResourceProvider}. {@code service.name} в Resource формирует
 * платформенный provider — по нему же идёт запрос в Jaeger.
 */
public final class ResourceIdentityAgentSmokeProcessRunner {

    private ResourceIdentityAgentSmokeProcessRunner() {
    }

    public static String run(String otelAgentJar,
                             String testRuntimeClasspath,
                             String otlpEndpoint,
                             String extensionLocation,
                             Map<String, String> platformServiceProps,
                             Duration processTimeout,
                             long flushDelayMs) throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");

        List<String> jvmProperties = new ArrayList<>();
        Path extensionPath = Path.of(extensionLocation);
        assertThat(Files.exists(extensionPath))
                .as("Extension JAR: %s", extensionLocation).isTrue();
        // На Windows Agent ожидает forward slashes в путях extension.
        jvmProperties.add("otel.javaagent.extensions=" + extensionPath.toString().replace('\\', '/'));
        jvmProperties.add("otel.traces.exporter=otlp");
        jvmProperties.add("otel.exporter.otlp.endpoint=" + otlpEndpoint);
        jvmProperties.add("otel.exporter.otlp.traces.endpoint="
                + JaegerTestContainerSupport.resolveOtlpHttpTracesEndpoint(otlpEndpoint));
        jvmProperties.add("otel.exporter.otlp.protocol=http/protobuf");
        jvmProperties.add("otel.traces.sampler=always_on");
        jvmProperties.add("platform.tracing.sampling.ratio=1.0");
        jvmProperties.add("otel.metrics.exporter=none");
        jvmProperties.add("otel.logs.exporter=none");
        jvmProperties.add("platform.tracing.queue.overflow-policy=UPSTREAM");
        jvmProperties.add("otel.bsp.schedule.delay=200");
        // Идентичность через платформенный namespace (путь PlatformResourceProvider).
        platformServiceProps.forEach((k, v) -> jvmProperties.add(k + "=" + v));

        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        // JVM contract: все -D до -javaagent, иначе premain Agent'а их не увидит.
        for (String property : jvmProperties) {
            command.add("-D" + property);
        }
        command.add("-javaagent:" + otelAgentJar);
        command.add("-cp");
        command.add(testRuntimeClasspath);
        command.add(ResourceIdentityAgentSmokeMain.class.getName());
        command.add(Long.toString(flushDelayMs));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);

        assertThat(finished)
                .as("Agent JVM должна завершиться за %s. Output:\n%s", processTimeout, output).isTrue();
        assertThat(process.exitValue())
                .as("Agent JVM exit code. Output:\n%s", output).isZero();
        assertThat(output)
                .as("OTel Java Agent должен стартовать без ошибок")
                .doesNotContain("OpenTelemetry Javaagent failed to start");
        return output;
    }
}
