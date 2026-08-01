package space.br1440.platform.tracing.autoconfigure.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.function.Supplier;

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
 * Agent-канал {@code platform.tracing.service.name} / {@code OTEL_SERVICE_NAME} задаёт OTel Resource
 * identity и <b>не</b> читается этим провайдером (см. {@code ADR-spring-owns-only-what-spring-applies}).
 * <p>
 * Значение разрешается один раз в конструкторе и кэшируется: имя сервиса задаётся
 * на старте JVM и не должно меняться в рантайме.
 * <p>
 * <b>Контракт §37 (non-blocking):</b> метод {@link #get()} никогда не выбрасывает исключений
 * и не выполняет блокирующих операций — это простое чтение закешированной строки.
 */
public final class PlatformLocalServiceNameProvider implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(PlatformLocalServiceNameProvider.class);

    /**
     * Значение по умолчанию, когда имя сервиса не задано.
     * Согласовано с OpenTelemetry SDK: при отсутствии {@code OTEL_SERVICE_NAME} ресурс
     * получает значение {@code unknown_service}.
     */
    public static final String UNKNOWN_SERVICE = "unknown_service";

    private static final String SPRING_APPLICATION_NAME = "spring.application.name";

    private final String localServiceName;

    public PlatformLocalServiceNameProvider(Environment environment) {
        this.localServiceName = resolve(environment);
        log.debug("Local service name resolved: '{}'", this.localServiceName);
    }

    @Override
    public String get() {
        return localServiceName;
    }

    private static String resolve(Environment environment) {
        String fromSpring = environment != null
                ? environment.getProperty(SPRING_APPLICATION_NAME) : null;
        if (isPresent(fromSpring)) {
            return fromSpring;
        }
        return UNKNOWN_SERVICE;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
