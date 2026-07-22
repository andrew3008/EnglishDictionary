package space.br1440.platform.tracing.otel.javaagent.resource;

import java.io.InputStream;

/**
 * Абстракция доступа к ресурсу classpath — точка подмены в unit-тестах
 * (без реального JAR/classloader'а).
 * <p>
 * Production-реализация {@link #tcclFirst()} ищет ресурс сначала через Thread Context
 * ClassLoader (TCCL), затем через classloader самого расширения. Порядок важен в Agent mode:
 * классы расширения грузятся изолированным {@code AgentClassLoader}, который <b>не видит</b>
 * ресурсы приложения (например {@code META-INF/build-info.properties}); TCCL во время
 * инициализации SDK указывает на classloader приложения и видит их.
 */
@FunctionalInterface
public interface ClasspathResourceLoader {

    /**
     * Открывает ресурс по пути относительно корня classpath.
     *
     * @param path путь ресурса (например {@code META-INF/build-info.properties})
     * @return поток ресурса либо {@code null}, если ресурс не найден
     */
    InputStream open(String path);

    /**
     * Production-загрузчик: <b>TCCL-first</b>, затем classloader расширения.
     *
     * @return загрузчик с приоритетом Thread Context ClassLoader
     */
    static ClasspathResourceLoader tcclFirst() {
        return path -> {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl != null) {
                InputStream fromTccl = tccl.getResourceAsStream(path);
                if (fromTccl != null) {
                    return fromTccl;
                }
            }
            // Fallback: classloader расширения (на случай, если TCCL пуст/не тот).
            return ClasspathResourceLoader.class.getClassLoader().getResourceAsStream(path);
        };
    }
}
