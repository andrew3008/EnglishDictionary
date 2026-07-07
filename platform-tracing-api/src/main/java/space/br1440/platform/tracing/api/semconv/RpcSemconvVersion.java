package space.br1440.platform.tracing.api.semconv;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks RPC transport builders that target a concrete OpenTelemetry semconv schema version.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface RpcSemconvVersion {

    /** Semconv schema version, e.g. {@code "1.28.0"}. */
    String value();
}
