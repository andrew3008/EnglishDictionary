package space.br1440.platform.tracing.api.semconv;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает Kafka transport builder'ы, ориентированные на конкретную версию
 * схемы OpenTelemetry semantic conventions.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface KafkaSemconvVersion {

    /** Версия схемы semconv, например {@code "1.28.0"}. */
    String value();

}
