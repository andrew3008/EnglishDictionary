package space.br1440.platform.tracing.e2e.support;

import space.br1440.platform.tracing.e2e.smoke.AgentJdbcSmokeMain;

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
 * Запуск дочерней JVM с OTel Java Agent и {@link AgentJdbcSmokeMain}.
 * <p>
 * Premain-инструментация невозможна внутри JUnit-процесса — Agent стартует отдельно.
 */
public final class AgentJdbcSmokeProcessRunner {

    private AgentJdbcSmokeProcessRunner() {
    }

    /**
     * @param otelAgentJar          путь к {@code opentelemetry-javaagent.jar}
     * @param testRuntimeClasspath  classpath для JDBC-драйвера и main-класса
     * @param otlpEndpoint          OTLP endpoint (http://host:port)
     * @param serviceName           {@code otel.service.name}
     * @param jdbcUrl               JDBC URL PostgreSQL
     * @param username              пользователь БД
     * @param password              пароль БД
     * @param extensionLocation     опционально: JAR или каталог для {@code otel.javaagent.extensions}
     * @param extraEnv              дополнительные переменные окружения (semconv opt-in и т.д.)
     * @param extraJvmSystemProperties дополнительные {@code -D...} для JVM (например sampling ratio)
     * @param processTimeout        таймаут дочернего процесса
     * @param flushDelayMs          задержка flush BSP в main-классе
     */
    public static String run(
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            String jdbcUrl,
            String username,
            String password,
            String extensionLocation,
            Map<String, String> extraEnv,
            List<String> extraJvmSystemProperties,
            Duration processTimeout,
            long flushDelayMs) throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");

        List<String> jvmProperties = new ArrayList<>();
        if (extensionLocation != null && !extensionLocation.isBlank()) {
            Path extensionPath = Path.of(extensionLocation);
            assertThat(Files.exists(extensionPath))
                    .as("Extension JAR или каталог: %s", extensionLocation)
                    .isTrue();
            // На Windows Agent ожидает forward slashes в путях extension.
            jvmProperties.add("otel.javaagent.extensions=" + extensionPath.toString().replace('\\', '/'));
        }
        jvmProperties.add("otel.service.name=" + serviceName);
        jvmProperties.add("otel.traces.exporter=otlp");
        jvmProperties.add("otel.exporter.otlp.endpoint=" + otlpEndpoint);
        jvmProperties.add("otel.exporter.otlp.protocol=http/protobuf");
        jvmProperties.add("platform.tracing.queue.overflow-policy=UPSTREAM");
        jvmProperties.add("otel.metrics.exporter=none");
        jvmProperties.add("otel.logs.exporter=none");
        if (extraJvmSystemProperties == null
                || extraJvmSystemProperties.stream().noneMatch(p -> p != null && p.startsWith("otel.bsp.schedule.delay"))) {
            jvmProperties.add("otel.bsp.schedule.delay=200");
        }
        jvmProperties.add("otel.instrumentation.jdbc.enabled=true");
        if (extraJvmSystemProperties != null) {
            for (String property : extraJvmSystemProperties) {
                if (property != null && !property.isBlank()) {
                    jvmProperties.add(property);
                }
            }
        }

        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        // JVM contract: все -D должны идти до -javaagent, иначе premain Agent'а их не увидит.
        for (String property : jvmProperties) {
            command.add("-D" + property);
        }
        command.add("-javaagent:" + otelAgentJar);
        command.add("-cp");
        command.add(testRuntimeClasspath);
        command.add(AgentJdbcSmokeMain.class.getName());
        command.add(jdbcUrl);
        command.add(username);
        command.add(password);
        command.add(Long.toString(flushDelayMs));

        ProcessBuilder builder = new ProcessBuilder(command);
        if (extraEnv != null) {
            builder.environment().putAll(extraEnv);
        }
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);

        assertThat(finished)
                .as("Agent JVM должна завершиться за %s. Output:\n%s", processTimeout, output)
                .isTrue();
        assertThat(process.exitValue())
                .as("Agent JVM exit code. Output:\n%s", output)
                .isZero();
        assertThat(output)
                .as("OTel Java Agent должен стартовать без ошибок")
                .doesNotContain("OpenTelemetry Javaagent failed to start");
        return output;
    }
}
