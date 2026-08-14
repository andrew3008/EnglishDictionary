package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

final class ConfigurationFingerprint {

    private static final String[] FINGERPRINT_KEYS = {
            ExtensionPropertyNames.SAMPLING_RATIO,
            ExtensionPropertyNames.SAMPLING_ENABLED,
            ExtensionPropertyNames.SAMPLING_ROUTE_RATIOS,
            ExtensionPropertyNames.SAMPLING_FORCE_RECORD_VALUES,
            ExtensionPropertyNames.SAMPLING_DROP_PATHS,
            ExtensionPropertyNames.ENRICHING_ENABLED,
            ExtensionPropertyNames.ENRICHING_REMOTE_SERVICE_PRIORITY,
            ExtensionPropertyNames.SCRUBBING_ENABLED,
            ExtensionPropertyNames.SCRUBBING_BUILT_IN_RULES,
            ExtensionPropertyNames.SCRUBBING_MISSING_KEY_POLICY,
            ExtensionPropertyNames.SCRUBBING_RULES_CONFIG,
            ExtensionPropertyNames.SCRUBBING_RULES_EXTENSIONS,
            ExtensionPropertyNames.SCRUBBING_RULES_VALIDATION_MODE,
            ExtensionPropertyNames.METRICS_ENABLED,
            ExtensionPropertyNames.VALIDATION_ENABLED,
            ExtensionPropertyNames.VALIDATION_MODE,
            ExtensionPropertyNames.PLATFORM_ENVIRONMENT,
            ExtensionPropertyNames.RESOURCE_POLICY_VERSION,
            ExtensionPropertyNames.RESOURCE_NORMALIZE_ENVIRONMENT,
            ExtensionPropertyNames.RESOURCE_VALIDATION_MODE,
            ExtensionPropertyNames.RESOURCE_DETECT_CONTAINER_ID,
            ExtensionPropertyNames.CLASSIFICATION_ENABLED,
            ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD,
            ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD,
            ExtensionPropertyNames.WATCHDOG_ENABLED,
            ExtensionPropertyNames.WATCHDOG_SPAN_TIMEOUT,
            ExtensionPropertyNames.WATCHDOG_TRACE_TIMEOUT,
            ExtensionPropertyNames.WATCHDOG_SCAN_INTERVAL,
            ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY,
            ExtensionPropertyNames.BAGGAGE_PROPAGATION_ENABLED,
            ExtensionPropertyNames.BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS,
            ExtensionPropertyNames.BAGGAGE_PROPAGATION_OUTBOUND_DENY_PATTERNS,
            ExtensionPropertyNames.BAGGAGE_PROCESSOR_ENABLED,
            ExtensionPropertyNames.BAGGAGE_PROCESSOR_ALLOWED_KEYS,
            ExtensionPropertyNames.BAGGAGE_PROCESSOR_DENY_PATTERNS,
    };

    private final String value;

    private ConfigurationFingerprint(String value) {
        this.value = value;
    }

    static ConfigurationFingerprint of(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        StringBuilder canonical = new StringBuilder();
        for (String key : FINGERPRINT_KEYS) {
            canonical.append(key).append('=').append(Objects.toString(config.getString(key), "")).append(';');
        }

        return new ConfigurationFingerprint(sha256Hex(canonical.toString()));
    }

    String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return (other instanceof ConfigurationFingerprint that) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 message digest is unavailable on this JVM", impossible);
        }
    }
}
