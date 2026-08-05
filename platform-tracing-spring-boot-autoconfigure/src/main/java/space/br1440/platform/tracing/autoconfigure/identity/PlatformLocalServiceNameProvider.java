package space.br1440.platform.tracing.autoconfigure.identity;

import java.util.function.Supplier;

import org.springframework.core.env.Environment;

/**
 * Поставщик логического имени текущего сервиса.
 * <p>
 * Назначение: внешний errorhandling-стартер потребителя инжектирует
 * {@code Supplier<String> platformLocalServiceNameProvider} и использует его значение для
 * заполнения поля {@code domain} top-level DTO ошибки
 * ({@code space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO#domain}).
 * <p>
 * Источники по убыванию приоритета:
 * <ol>
 *   <li>{@code spring.application.name} — стандартное Spring Boot свойство; то же значение
 *       по умолчанию использует {@code PlatformResourceProvider} как fallback для ресурсного
 *       атрибута {@code service.name}.</li>
 *   <li>Константа {@value #UNKNOWN_SERVICE} — финальный fallback, согласованный с
 *       <a href="https://opentelemetry.io/docs/specs/semconv/resource/#service">OpenTelemetry SDK
 *       по умолчанию</a> для отсутствующего {@code service.name}.</li>
 * </ol>
 * <p>
 * Значение разрешается один раз в конструкторе и кэшируется: имя сервиса задаётся
 * на старте JVM и не должно меняться в рантайме.
 */
public final class PlatformLocalServiceNameProvider implements Supplier<String> {

    /**
     * Значение по умолчанию, когда имя сервиса не задано.
     * <p>
     * Согласовано с <a href="https://opentelemetry.io/docs/specs/semconv/resource/#service">
     * OpenTelemetry Semantic Conventions — Service</a>: SDK MUST использовать
     * {@code unknown_service}, конкатенированный с {@code process.executable.name}
     * (например, {@code unknown_service:java}), либо просто {@code unknown_service},
     * если имя процесса недоступно.
     */
    public static final String UNKNOWN_SERVICE = "unknown_service";

    private static final String SPRING_APPLICATION_NAME = "spring.application.name";

    private final String localServiceName;

    public PlatformLocalServiceNameProvider(Environment environment) {
        this.localServiceName = resolve(environment);
    }

    @Override
    public String get() {
        return localServiceName;
    }

    private static String resolve(Environment environment) {
        String fromSpring = (environment != null) ? environment.getProperty(SPRING_APPLICATION_NAME) : null;
        if (isPresent(fromSpring)) {
            return fromSpring;
        }

        return UNKNOWN_SERVICE;
    }

    private static boolean isPresent(String value) {
        return (value != null) && !value.isBlank();
    }
}
