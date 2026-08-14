package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;

public final class ResourceValidationDiagnostics {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> C_GROUP = AttributeKey.stringKey(PlatformAttributes.PLATFORM_C_GROUP);

    public record ValidationResult(boolean passed, List<String> missingKeys, ResourceValidationMode mode) {
    }

    public ValidationResult validate(Attributes resolved,
                                     Resource otelEnv,
                                     Resource existing,
                                     ResourceValidationMode mode) {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(otelEnv, "otelEnv");
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(mode, "mode");

        List<String> missing = new ArrayList<>();

        if (!hasRealServiceName(resolved, otelEnv, existing)) {
            missing.add(PlatformAttributes.SERVICE_NAME);
        }

        if (isUnknownOrBlank(ENVIRONMENT, resolved, otelEnv, existing)) {
            missing.add(PlatformAttributes.PLATFORM_ENVIRONMENT);
        }

        if (isUnknownOrBlank(C_GROUP, resolved, otelEnv, existing)) {
            missing.add(PlatformAttributes.PLATFORM_C_GROUP);
        }

        return new ValidationResult(missing.isEmpty(), List.copyOf(missing), mode);
    }

    public void applyOrThrow(ValidationResult result) {
        Objects.requireNonNull(result, "result");
        if (result.passed()) {
            return;
        }

        String keys = String.join(", ", result.missingKeys());
        if (result.mode() == ResourceValidationMode.STRICT) {
            throw new IllegalStateException("Resource identity failed STRICT validation; missing required keys [" + keys + "]");
        }
    }

    private static boolean hasRealServiceName(Attributes resolved,
                                              Resource otelEnv,
                                              Resource existing) {
        String name = firstNonNull(
                resolved.get(SERVICE_NAME),
                otelEnv.getAttribute(SERVICE_NAME),
                existing.getAttribute(SERVICE_NAME));

        return Strings.isNotBlank(name)
                && !ResourceAttributeResolver.SDK_UNKNOWN_SERVICE.equals(name)
                && !name.startsWith("unknown_service");
    }

    private static boolean isUnknownOrBlank(AttributeKey<String> key,
                                            Attributes resolved,
                                            Resource otelEnv,
                                            Resource existing) {
        String value = firstNonNull(resolved.get(key), otelEnv.getAttribute(key), existing.getAttribute(key));
        return Strings.isBlank(value) || EnvironmentNormalizer.UNKNOWN.equals(value);
    }

    private static String firstNonNull(String a, String b, String c) {
        if (a != null) {
            return a;
        }

        if (b != null) {
            return b;
        }

        return c;
    }
}
