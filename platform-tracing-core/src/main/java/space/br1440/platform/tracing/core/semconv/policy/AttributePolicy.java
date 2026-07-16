package space.br1440.platform.tracing.core.semconv.policy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.core.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.semconv.SemconvViolation;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Валидация платформенного semantic-контракта. Принимает {@link CategoryContract} из
 * единого реестра {@link CategoryContracts}.
 * <p>
 * Главный метод — {@link #validateAndNormalize}: возвращает единый нормализованный snapshot
 * атрибутов (для имени span'а и для {@code SpanBuilder}). Вызывается до {@code startSpan()}
 * (sampler видит уже нормализованные creation-time атрибуты).
 * <p>
 * Поведение по {@link SemconvValidationMode}:
 * <ul>
 *   <li>{@code STRICT} — бросает {@link SemconvViolationException} на первом нарушении (fail-fast);</li>
 *   <li>{@code WARN} — safe-defaults (только для platform-required {@code platform.trace.type}),
 *       лог (once per rule+builder) и метрика {@code platform.tracing.semconv.violations};</li>
 *   <li>{@code DISABLED} — атрибуты as-is, без дефолтов/логов/метрик.</li>
 * </ul>
 */
@Slf4j
public final class AttributePolicy {

    /** Максимальная длина имени ключа для escape-hatch (защита от мусора). */
    private static final int MAX_KEY_LENGTH = 255;

    /**
     * Все известные платформе ключи (объединение allowlist всех категорий) — для классификации
     * escape-hatch ключей как {@code known}/{@code unknown}.
     */
    private static final Set<String> KNOWN_KEYS = buildKnownKeys();

    private final SemconvValidationMode mode;
    private final boolean allowUnsafeAttributes;
    private final SemconvMetrics metrics;

    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    public AttributePolicy(@Nonnull SemconvValidationMode mode,
                           boolean allowUnsafeAttributes,
                           @Nonnull SemconvMetrics metrics) {
        this.mode = mode;
        this.allowUnsafeAttributes = allowUnsafeAttributes;
        this.metrics = metrics;
    }

    public AttributePolicy() {
        this(SemconvValidationMode.WARN, false, SemconvMetrics.NOOP);
    }

    @Nonnull
    public SemconvValidationMode mode() {
        return mode;
    }

    public boolean isAllowed(@Nonnull SpanCategory category, @Nonnull AttributeKey<?> key) {
        return CategoryContracts.of(category).allowlist().contains(key);
    }

    @Nonnull
    CategoryContract contractFor(@Nonnull SpanCategory category) {
        return CategoryContracts.of(category);
    }

    @Nonnull
    public ValidatedAttributes validateAndNormalize(@Nonnull SpanCategory category,
                                                    @Nonnull Attributes accumulated,
                                                    @Nonnull String builderName) {
        if (mode == SemconvValidationMode.DISABLED) {
            return new ValidatedAttributes(accumulated, List.of());
        }

        CategoryContract contract = CategoryContracts.of(category);
        List<SemconvViolation> violations = collectViolations(contract, accumulated, category, builderName);

        if (mode == SemconvValidationMode.STRICT && !violations.isEmpty()) {
            throw new SemconvViolationException(violations.getFirst());
        }

        for (SemconvViolation v : violations) {
            metrics.violation(v.ruleId(), v.builder());
            warnOnce(v);
        }

        AttributesBuilder normalized = accumulated.toBuilder();
        applySafeDefaults(normalized, category, accumulated);
        return new ValidatedAttributes(normalized.build(), violations);
    }

    @Nonnull
    public UnsafeKeyClass auditUnsafeAttribute(@Nonnull String key) {
        UnsafeKeyClass kc = classify(key);
        metrics.unsafeAttribute(kc.metricValue());
        if (kc != UnsafeKeyClass.REJECTED) {
            log.warn("unsafeAttribute key={} class={}", sanitizeKeyForLog(key), kc);
        }

        return kc;
    }

    private UnsafeKeyClass classify(String key) {
        if (!allowUnsafeAttributes) {
            return UnsafeKeyClass.REJECTED;
        }

        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            return UnsafeKeyClass.REJECTED;
        }

        return KNOWN_KEYS.contains(key) ? UnsafeKeyClass.KNOWN : UnsafeKeyClass.UNKNOWN;
    }

    private List<SemconvViolation> collectViolations(CategoryContract contract,
                                                     Attributes accumulated,
                                                     SpanCategory category,
                                                     String builderName) {
        List<SemconvViolation> violations = new ArrayList<>();
        Set<AttributeKey<?>> present = accumulated.asMap().keySet();

        for (AttributeKey<?> key : present) {
            if (contract.forbidden().contains(key)) {
                violations.add(new SemconvViolation("ATTR_FORBIDDEN", category, builderName,
                        key.getKey(), "forbidden attribute '" + key.getKey() + "' for category " + category));
            } else if (!contract.allowlist().contains(key)) {
                violations.add(new SemconvViolation("ATTR_NOT_ALLOWED", category, builderName,
                        key.getKey(), "attribute '" + key.getKey() + "' is not in the allowlist for category " + category));
            }
        }

        for (AttributeKey<?> req : contract.required()) {
            if (!present.contains(req)) {
                violations.add(new SemconvViolation("REQUIRED_MISSING", category, builderName,
                        req.getKey(), "required attribute '" + req.getKey() + "' is missing"));
            }
        }

        for (Set<AttributeKey<?>> anyOf : contract.requiredAnyOf()) {
            boolean satisfied = anyOf.stream().anyMatch(present::contains);
            if (!satisfied) {
                String names = anyOf.stream().map(AttributeKey::getKey).sorted().toList().toString();
                violations.add(new SemconvViolation("REQUIRED_ANY_OF_MISSING", category, builderName,
                        null, "at least one of " + names + " must be present"));
            }
        }

        return violations;
    }

    private void applySafeDefaults(AttributesBuilder normalized, SpanCategory category, Attributes accumulated) {
        if (accumulated.get(SemconvKeys.PLATFORM_TYPE) == null) {
            normalized.put(SemconvKeys.PLATFORM_TYPE, category.value());
        }
    }

    private void warnOnce(SemconvViolation v) {
        String dedupKey = v.ruleId() + '|' + v.builder() + '|' + (v.attributeKey() == null ? "" : v.attributeKey());
        if (warnedOnce.add(dedupKey)) {
            log.warn("semconv violation [{}] builder={} category={}: {}",
                    v.ruleId(), v.builder(), v.category(), v.message());
        } else if (log.isDebugEnabled()) {
            log.debug("semconv violation [{}] builder={} (repeat)", v.ruleId(), v.builder());
        }
    }

    static String sanitizeKeyForLog(String key) {
        if (key == null) {
            return "<null>";
        }

        int dot = key.indexOf('.');
        if (dot <= 0) {
            return key.length() <= 8 ? key : key.substring(0, 8) + "…";
        }

        return key.substring(0, dot) + ".***(" + (key.length() - dot - 1) + " chars)";
    }

    private static Set<String> buildKnownKeys() {
        Set<String> keys = new HashSet<>();
        for (SpanCategory category : SpanCategory.values()) {
            for (AttributeKey<?> key : CategoryContracts.of(category).allowlist()) {
                keys.add(key.getKey());
            }
        }

        return Set.copyOf(keys);
    }

    public enum UnsafeKeyClass {
        /** Ключ известен платформе (в allowlist какой-либо категории / SemconvKeys). */
        KNOWN("known"),
        /** Ключ платформе неизвестен, но допущен (allowUnsafeAttributes=true). */
        UNKNOWN("unknown"),
        /** Ключ отклонён (escape-hatch запрещён, либо имя невалидно). */
        REJECTED("rejected");

        private final String metricValue;

        UnsafeKeyClass(String metricValue) {
            this.metricValue = metricValue;
        }

        public String metricValue() {
            return metricValue;
        }
    }
}
