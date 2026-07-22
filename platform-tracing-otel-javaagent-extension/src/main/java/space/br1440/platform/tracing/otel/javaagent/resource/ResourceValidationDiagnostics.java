package space.br1440.platform.tracing.otel.javaagent.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import java.util.ArrayList;
import java.util.List;

/**
 * Валидация обязательных resource-ключей идентичности на старте (требование §8 Traces Requests.txt:
 * не отправлять телеметрию без идентичности — реализовано как fail-fast на старте, что строже
 * блокировки одного span'а).
 *
 * <h2>Effective-view (защита от false-positive)</h2>
 * Required-ключ считается присутствующим, если он есть <b>в любом</b> из источников:
 * {@code resolved} (то, что дополнила платформа), {@code otelEnv} (заданное через {@code OTEL_*})
 * или {@code existing} (предыдущие провайдеры). Без учёта {@code otelEnv} STRICT ложно падал бы
 * при заданном {@code OTEL_SERVICE_NAME}, т.к. платформа в этом случае омитит {@code service.name}.
 */
public final class ResourceValidationDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(ResourceValidationDiagnostics.class);

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> C_GROUP = AttributeKey.stringKey(PlatformAttributes.PLATFORM_C_GROUP);

    /** Результат валидации: прошла ли, список отсутствующих required-ключей, режим. */
    public record ValidationResult(boolean passed, List<String> missingKeys, ResourceValidationMode mode) {
    }

    /**
     * Проверяет required-ключи по effective-view.
     *
     * @param resolved атрибуты, дополненные платформой
     * @param otelEnv  Resource из {@code OTEL_*} ({@code ResourceConfiguration.createEnvironmentResource})
     * @param existing накопленный ранее Resource
     * @param mode     режим валидации
     */
    public ValidationResult validate(Attributes resolved, Resource otelEnv, Resource existing,
                                     ResourceValidationMode mode) {
        List<String> missing = new ArrayList<>();

        if (!hasRealServiceName(resolved, otelEnv, existing)) {
            missing.add(PlatformAttributes.SERVICE_NAME);
        }
        if (!hasNonUnknown(ENVIRONMENT, resolved, otelEnv, existing)) {
            missing.add(PlatformAttributes.PLATFORM_ENVIRONMENT);
        }
        if (!hasNonUnknown(C_GROUP, resolved, otelEnv, existing)) {
            missing.add(PlatformAttributes.PLATFORM_C_GROUP);
        }

        return new ValidationResult(missing.isEmpty(), List.copyOf(missing), mode);
    }

    /**
     * Применяет результат: STRICT → {@link IllegalStateException}; LENIENT → один WARN.
     * При успешной валидации — no-op.
     */
    public void applyOrThrow(ValidationResult result) {
        if (result.passed()) {
            return;
        }
        String keys = String.join(", ", result.missingKeys());
        if (result.mode() == ResourceValidationMode.STRICT) {
            throw new IllegalStateException(
                    "Resource-идентичность не прошла STRICT-валидацию: отсутствуют обязательные ключи [" + keys + "]");
        }
        log.warn("Resource-идентичность неполна (LENIENT): отсутствуют обязательные ключи [{}]", keys);
    }

    private static boolean hasRealServiceName(Attributes resolved, Resource otelEnv, Resource existing) {
        String name = firstNonNull(
                resolved.get(SERVICE_NAME),
                otelEnv.getAttribute(SERVICE_NAME),
                existing.getAttribute(SERVICE_NAME));
        return Strings.isNotBlank(name)
                && !ResourceAttributeResolver.SDK_UNKNOWN_SERVICE.equals(name)
                && !name.startsWith("unknown_service");
    }

    private static boolean hasNonUnknown(AttributeKey<String> key, Attributes resolved, Resource otelEnv,
                                         Resource existing) {
        String value = firstNonNull(resolved.get(key), otelEnv.getAttribute(key), existing.getAttribute(key));
        return Strings.isNotBlank(value) && !EnvironmentNormalizer.UNKNOWN.equals(value);
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
