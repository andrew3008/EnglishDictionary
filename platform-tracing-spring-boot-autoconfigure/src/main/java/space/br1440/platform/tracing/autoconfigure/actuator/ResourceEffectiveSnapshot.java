package space.br1440.platform.tracing.autoconfigure.actuator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * «Эффективная» resource-идентичность сервиса, видимая через {@code GET /actuator/tracing}
 * (секция {@code resourceEffective}).
 * <p>
 * Назначение — диагностика drift между {@code application.yml} (Spring), платформенным env-каналом
 * {@code PLATFORM_TRACING_*} и стандартным {@code OTEL_*}. Снапшот строится из {@link System}
 * свойств и переменных окружения (эндпоинт живёт в classloader'е приложения, реальные
 * {@code ConfigProperties} — в classloader'е agent'а), то есть показывает то, что оператор увидит
 * на хосте.
 *
 * <p>Для каждого identity-атрибута перебираются источники по приоритету (system-property → env);
 * первый непустой фиксируется вместе с пометкой источника. {@code service.version}, не заданный
 * явно, приходит из {@code build-info.properties} (вариант C) — это помечается как
 * {@code source=build-info-or-absent}.
 */
final class ResourceEffectiveSnapshot {

    /** Логический атрибут identity и упорядоченные кандидаты (system property, env var). */
    private record IdentityKey(String attribute, List<String[]> candidates, boolean buildInfoFallback) {
    }

    private static final List<IdentityKey> KEYS = List.of(
            new IdentityKey("service.name", List.<String[]>of(
                    new String[]{"otel.service.name", "OTEL_SERVICE_NAME"},
                    new String[]{"platform.tracing.service.name", "PLATFORM_TRACING_SERVICE_NAME"}), false),
            new IdentityKey("service.version", List.<String[]>of(
                    new String[]{"platform.tracing.service.version", "PLATFORM_TRACING_SERVICE_VERSION"}), true),
            new IdentityKey("deployment.environment.name", List.<String[]>of(
                    new String[]{"platform.tracing.service.environment", "PLATFORM_TRACING_SERVICE_ENVIRONMENT"}), false),
            new IdentityKey("platform.c_group", List.<String[]>of(
                    new String[]{"platform.tracing.service.c-group", "PLATFORM_TRACING_SERVICE_C_GROUP"}), false),
            new IdentityKey("platform.tracing.policy.version", List.<String[]>of(
                    new String[]{"platform.tracing.resource.policy-version", "PLATFORM_TRACING_RESOURCE_POLICY_VERSION"}), false)
    );

    private final Function<String, String> systemPropertyLookup;
    private final Function<String, String> envLookup;

    ResourceEffectiveSnapshot() {
        this(System::getProperty, System::getenv);
    }

    /** Конструктор для unit-тестов: подмена источников. */
    ResourceEffectiveSnapshot(Function<String, String> systemPropertyLookup, Function<String, String> envLookup) {
        this.systemPropertyLookup = systemPropertyLookup;
        this.envLookup = envLookup;
    }

    /**
     * @return карта {@code (resource attribute → source + value + sourceKey)}; никогда не {@code null}
     */
    public Map<String, Map<String, Object>> build() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (IdentityKey key : KEYS) {
            Map<String, Object> entry = new LinkedHashMap<>();
            boolean resolved = false;
            for (String[] candidate : key.candidates()) {
                String prop = candidate[0];
                String env = candidate[1];
                String sysValue = systemPropertyLookup.apply(prop);
                String envValue = envLookup.apply(env);
                if (sysValue != null && !sysValue.isBlank()) {
                    entry.put("source", "system-property");
                    entry.put("sourceKey", prop);
                    entry.put("value", sysValue.trim());
                    resolved = true;
                    break;
                }
                if (envValue != null && !envValue.isBlank()) {
                    entry.put("source", "env-var");
                    entry.put("sourceKey", env);
                    entry.put("value", envValue.trim());
                    resolved = true;
                    break;
                }
            }
            if (!resolved) {
                entry.put("source", key.buildInfoFallback() ? "build-info-or-absent" : "absent");
                entry.put("value", null);
            }
            result.put(key.attribute(), entry);
        }
        return result;
    }
}
