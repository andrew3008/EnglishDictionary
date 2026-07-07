package space.br1440.platform.tracing.autoconfigure.support;

import org.springframework.util.ClassUtils;

/**
 * Утилита определения присутствия OpenTelemetry Java Agent в текущей JVM.
 * <p>
 * Внутреннее назначение: используется autoconfigure-логикой (например, для активации
 * подавления дублирующих HTTP-span'ов от Micrometer Observation, когда HTTP-span'ы уже
 * создаёт Agent на уровне Tomcat/Netty).
 * <p>
 * <b>Алгоритм детекта:</b> {@code ClassUtils.isPresent("io.opentelemetry.javaagent.OpenTelemetryAgent",
 * null)} — проверка проводится через системный classloader (передача {@code null} в
 * {@link ClassUtils#isPresent(String, ClassLoader)} использует {@code ClassUtils.getDefaultClassLoader()},
 * который при необходимости поднимается до системного). OpenTelemetry Java Agent инжектит свои
 * классы в bootstrap classloader, и они становятся видимыми через любой потомок системного
 * classloader'а.
 * <p>
 * Класс-утилита: не предоставляет публичных конструкторов и не подлежит инстанцированию.
 */
public final class OtelAgentDetector {

    private static final String AGENT_MARKER_CLASS = "io.opentelemetry.javaagent.OpenTelemetryAgent";

    private OtelAgentDetector() {
        // Утилитный класс — не инстанцируется.
    }

    /**
     * Возвращает {@code true}, если в текущей JVM подключён OpenTelemetry Java Agent.
     * <p>
     * Метод детерминирован и не выполняет ни одного блокирующего I/O — пригоден для вызова
     * на этапе инициализации Spring-контекста.
     */
    public static boolean isAgentPresent() {
        return ClassUtils.isPresent(AGENT_MARKER_CLASS, null);
    }
}
