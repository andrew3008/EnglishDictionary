package space.br1440.platform.tracing.otel.javaagent.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SP-02: Requested or effective validation mode for {@link
 * space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor}.
 * <p>
 * The primary configuration property is {@code platform.tracing.validation.mode}. The legacy
 * boolean property {@code platform.tracing.validation.strict} is supported as a fallback when
 * the new property is absent — see {@link
 * space.br1440.platform.tracing.otel.javaagent.configuration.ValidationExtensionConfig}.
 * <p>
 * Accepted values for {@code platform.tracing.validation.mode} (case-insensitive):
 * <ul>
 *   <li>{@code LENIENT} — validate and warn on missing attributes; never throw</li>
 *   <li>{@code STRICT} — validate and throw {@code TracingValidationException} on missing
 *       attributes; allowed only in {@link PlatformEnvironment#DEV} and
 *       {@link PlatformEnvironment#STAGING}</li>
 *   <li>{@code DISABLED} — skip validation entirely; allowed everywhere with risk visibility</li>
 * </ul>
 * Unknown or blank values default to {@link #LENIENT}.
 */
public enum ValidationMode {

    LENIENT,
    STRICT,
    DISABLED;

    private static final Logger log = LoggerFactory.getLogger(ValidationMode.class);

    /**
     * Parses a {@code platform.tracing.validation.mode} value.
     * Returns {@link #LENIENT} and logs a warning for any blank or unrecognized value.
     */
    public static ValidationMode parse(String value) {
        if (value == null || value.isBlank()) {
            return LENIENT;
        }
        return switch (value.trim().toUpperCase()) {
            case "LENIENT" -> LENIENT;
            case "STRICT" -> STRICT;
            case "DISABLED" -> DISABLED;
            default -> {
                log.warn("SP-02: Unrecognized platform.tracing.validation.mode='{}'; defaulting to LENIENT", value);
                yield LENIENT;
            }
        };
    }
}
