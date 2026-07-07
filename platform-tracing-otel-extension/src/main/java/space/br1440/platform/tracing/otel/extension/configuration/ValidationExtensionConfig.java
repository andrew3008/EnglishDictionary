package space.br1440.platform.tracing.otel.extension.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames.*;

@Getter
@Accessors(fluent = true)
public final class ValidationExtensionConfig {

    private final boolean enabled;
    /** @deprecated Use {@link #requestedValidationMode()} with {@link ValidationModeResolver}. */
    @Deprecated
    private final boolean strict;
    /** @deprecated Replaced by environment-aware guard from {@link #platformEnvironment()}. */
    @Deprecated
    private final boolean strictRuntimeAllowed;

    /**
     * SP-02: Control-plane environment parsed from {@code platform.environment}.
     * NEVER derived from {@code deployment.environment.name}.
     */
    private final PlatformEnvironment platformEnvironment;

    /**
     * SP-02: Requested validation mode. Resolved from {@code platform.tracing.validation.mode}
     * if present; otherwise falls back to the legacy {@code platform.tracing.validation.strict}
     * boolean flag for backward compatibility.
     * <p>
     * Call {@link ValidationModeResolver#resolve(PlatformEnvironment, ValidationMode)} to obtain
     * the effective mode after applying the environment safety guard.
     */
    private final ValidationMode requestedValidationMode;

    ValidationExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(VALIDATION_ENABLED, DEFAULT_ENABLED);

        // SP-02: platform.environment — control-plane signal, not telemetry metadata.
        this.platformEnvironment = PlatformEnvironment.parse(reader.nullableString(PLATFORM_ENVIRONMENT));

        // SP-02: New primary property takes priority; legacy strict boolean is a fallback.
        String modeStr = reader.nullableString(VALIDATION_MODE);
        if (modeStr != null) {
            this.requestedValidationMode = ValidationMode.parse(modeStr);
        } else {
            boolean legacyStrict = reader.booleanValue(VALIDATION_STRICT, DEFAULT_VALIDATION_STRICT);
            this.requestedValidationMode = legacyStrict ? ValidationMode.STRICT : ValidationMode.LENIENT;
        }

        // Legacy fields retained for compatibility with existing callers and tests.
        this.strict = reader.booleanValue(VALIDATION_STRICT, DEFAULT_VALIDATION_STRICT);
        this.strictRuntimeAllowed = reader.booleanValue(VALIDATION_STRICT_RUNTIME_ALLOWED, DEFAULT_VALIDATION_STRICT_RUNTIME_ALLOWED);
    }
}
