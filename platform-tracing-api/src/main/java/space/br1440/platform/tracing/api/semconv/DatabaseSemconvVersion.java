package space.br1440.platform.tracing.api.semconv;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the OpenTelemetry Database semantic conventions version implemented by the
 * annotated database transport builder. Mirrors {@link KafkaSemconvVersion} /
 * {@link RpcSemconvVersion} (remediation B07).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface DatabaseSemconvVersion {

    /** Semconv schema version, e.g. {@code "1.28.0"}. */
    String value();
}
