package space.br1440.platform.tracing.core.semconv.policy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.api.semconv.CategoryContract;
import space.br1440.platform.tracing.api.semconv.CategoryContracts;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.semconv.SemconvViolation;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Движок валидации платформенного semantic-контракта. Потребляет {@link CategoryContract} из
 * единого реестра {@link CategoryContracts} (тот же экземпляр, что и будущий линтер — дрейф
 * невозможен структурно).
 * <p>
 * Главный метод — {@link #validateAndNormalize}: возвращает ЕДИНЫЙ нормализованный snapshot
 * атрибутов (для имени span'а и для {@code SpanBuilder}), а не void-{@code enforce}. Вызывается
 * ДО {@code startSpan()} (sampler видит уже нормализованные creation-time атрибуты).
 * <p>
 * Поведение по {@link ValidationMode}:
 * <ul>
 *   <li>{@code STRICT} — бросает {@link SemconvViolationException} на первом нарушении (fail-fast);</li>
 *   <li>{@code WARN} — safe-defaults (только для platform-required {@code platform.trace.type}),
 *       лог (once per rule+builder) и метрика {@code platform.tracing.semconv.violations};</li>
 *   <li>{@code DISABLED} — атрибуты as-is, без дефолтов/логов/метрик.</li>
 * </ul>
 * Класс потоко-безопасен: состояние неизменяемо, кроме набора уже залогированных ключей.
 */
public final class AttributePolicy {

    private static final Logger log = LoggerFactory.getLogger(AttributePolicy.class);

    /** Максимальная длина имени ключа для escape-hatch (защита от мусора). */
    private static final int MAX_KEY_LENGTH = 255;

    /**
     * Все известные платформе ключи (объединение allowlist всех категорий) — для классификации
     * escape-hatch ключей как {@code known}/{@code unknown}.
     */
    private static final Set<String> KNOWN_KEYS = buildKnownKeys();

    private final ValidationMode mode;
    private final boolean allowUnsafeAttributes;
    private final SemconvMetrics metrics;

    /** Лог-once guard (rate-limit без внешних зависимостей): WARN один раз на rule+builder. */
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    public AttributePolicy(@Nonnull ValidationMode mode,
                           boolean allowUnsafeAttributes,
                           @Nonnull SemconvMetrics metrics) {
        this.mode = mode;
        this.allowUnsafeAttributes = allowUnsafeAttributes;
        this.metrics = metrics;
    }

    /** Удобный конструктор: WARN, unsafe запрещён, метрики no-op (для SDK-only/тестов). */
    public AttributePolicy() {
        this(ValidationMode.WARN, false, SemconvMetrics.NOOP);
    }

    @Nonnull
    public ValidationMode mode() {
        return mode;
    }

    /** Контракт категории из единого реестра (тот же экземпляр потребляет линтер). */
    @Nonnull
    public CategoryContract contractFor(@Nonnull SpanCategory category) {
        return CategoryContracts.of(category);
    }

    /**
     * Валидирует накопленные (типизированные) атрибуты против контракта категории и возвращает
     * нормализованный snapshot. См. javadoc класса о режимах.
     *
     * @param category    категория span'а
     * @param accumulated накопленные типизированные атрибуты (без escape-hatch unsafe-ключей)
     * @param builderName имя builder'а/источника (тег метрики)
     */
    @Nonnull
    public ValidatedAttributes validateAndNormalize(@Nonnull SpanCategory category,
                                                    @Nonnull Attributes accumulated,
                                                    @Nonnull String builderName) {
        if (mode == ValidationMode.DISABLED) {
            return new ValidatedAttributes(accumulated, List.of());
        }

        CategoryContract contract = CategoryContracts.of(category);
        List<SemconvViolation> violations = collectViolations(contract, accumulated, category, builderName);

        if (mode == ValidationMode.STRICT && !violations.isEmpty()) {
            throw new SemconvViolationException(violations.get(0));
        }

        // WARN: self-diagnostics + safe-defaults (только platform-required platform.trace.type).
        for (SemconvViolation v : violations) {
            metrics.violation(v.ruleId(), v.builder());
            warnOnce(v);
        }
        AttributesBuilder normalized = accumulated.toBuilder();
        applySafeDefaults(normalized, category, accumulated);
        return new ValidatedAttributes(normalized.build(), violations);
    }

    /**
     * Аудит escape-hatch ключа {@code unsafeAttribute}. Инкрементирует метрику
     * {@code platform.tracing.unsafe_attributes{key_class}} и пишет sanitized-лог. Возвращает
     * класс ключа; при {@link UnsafeKeyClass#REJECTED} вызывающий builder НЕ должен записывать
     * атрибут.
     *
     * @param key имя ключа escape-hatch атрибута
     */
    @Nonnull
    public UnsafeKeyClass auditUnsafeAttribute(@Nonnull String key) {
        UnsafeKeyClass kc = classify(key);
        metrics.unsafeAttribute(kc.metricValue());
        if (kc != UnsafeKeyClass.REJECTED) {
            // В лог НЕ кладём raw key: он может быть high-cardinality / PII-подобным
            // (например "customer.123456.email"). Маскируем.
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

        // 1. allowlist / forbidden
        for (AttributeKey<?> key : present) {
            if (contract.forbidden().contains(key)) {
                violations.add(new SemconvViolation("ATTR_FORBIDDEN", category, builderName,
                        key.getKey(), "запрещённый атрибут '" + key.getKey() + "' для категории " + category));
            } else if (!contract.allowlist().contains(key)) {
                violations.add(new SemconvViolation("ATTR_NOT_ALLOWED", category, builderName,
                        key.getKey(), "атрибут '" + key.getKey() + "' вне allowlist категории " + category));
            }
        }

        // 2. required
        for (AttributeKey<?> req : contract.required()) {
            if (!present.contains(req)) {
                violations.add(new SemconvViolation("REQUIRED_MISSING", category, builderName,
                        req.getKey(), "обязательный атрибут '" + req.getKey() + "' отсутствует"));
            }
        }

        // 3. requiredAnyOf (dual-attribute)
        for (Set<AttributeKey<?>> anyOf : contract.requiredAnyOf()) {
            boolean satisfied = anyOf.stream().anyMatch(present::contains);
            if (!satisfied) {
                String names = anyOf.stream().map(AttributeKey::getKey).sorted().toList().toString();
                violations.add(new SemconvViolation("REQUIRED_ANY_OF_MISSING", category, builderName,
                        null, "должен присутствовать хотя бы один из " + names));
            }
        }

        return violations;
    }

    /**
     * Safe-defaults ТОЛЬКО для безопасных platform-required полей. Сейчас это
     * {@code platform.trace.type} = значение категории (builder обычно его уже ставит сам).
     */
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

    /**
     * Маскирует ключ для лога: оставляет первый сегмент пути (до точки) и длину остального,
     * чтобы не печатать потенциально PII-подобные сегменты целиком.
     */
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

    /** Класс escape-hatch ключа для метрики/аудита. */
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
