package space.br1440.platform.tracing.api.propagation;

import lombok.experimental.UtilityClass;

import java.util.ServiceLoader;

/**
 * Единая точка разрешения {@link RequestIdSupport} через {@link ServiceLoader} SPI.
 * <p>
 * Реализация ({@code RequestIdSupportImpl} из {@code platform-tracing-core})
 * регистрируется через {@code META-INF/services} и не требует Spring-контекста,
 * что делает её доступной в agent classloader ({@code platform-tracing-otel-extension}).
 * <p>
 * <b>Требование к runtime classpath:</b> {@code platform-tracing-core}
 * (или test-double, зарегистрированный под тем же service interface) должен
 * присутствовать в runtime classpath. При его отсутствии static initializer
 * бросит {@link IllegalStateException} на этапе загрузки класса.
 * <p>
 * Прикладной код не должен вызывать этот holder напрямую.
 * Используйте {@code TraceResponseHeader*Filter} или метод
 * {@code InboundTraceControl.fromHeaders()} для работы с correlation id.
 */
@UtilityClass
public final class RequestIdSupports {

    private static final RequestIdSupport INSTANCE =
            ServiceLoader.load(RequestIdSupport.class)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("""
                            No RequestIdSupport implementation found on classpath. \
                            Ensure platform-tracing-core is present at runtime."""));

    /**
     * Возвращает единственную зарегистрированную реализацию {@link RequestIdSupport}.
     *
     * @return экземпляр {@code RequestIdSupportImpl} из {@code platform-tracing-core}
     */
    public static RequestIdSupport get() {
        return INSTANCE;
    }
}
