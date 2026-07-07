package space.br1440.platform.tracing.semconv.lint.rules;

import space.br1440.platform.tracing.semconv.lint.LintRule;
import space.br1440.platform.tracing.semconv.lint.LintSeverity;
import space.br1440.platform.tracing.semconv.lint.LintViolation;
import space.br1440.platform.tracing.semconv.lint.SpanRecord;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Базовое правило для одного атрибута.
 * <p>
 * Поддерживает три ортогональных проверки:
 * <ul>
 *   <li>{@code required} — атрибут обязан присутствовать (либо на span'е, либо на ресурсе).</li>
 *   <li>{@code allowedValues} — если атрибут присутствует, его значение должно входить в набор.</li>
 *   <li>{@code valuePattern} — если атрибут присутствует, его значение должно соответствовать regex.</li>
 * </ul>
 * Все три проверки применяются независимо и могут срабатывать одновременно; в отчёте каждое
 * нарушение возвращается отдельным {@link LintViolation}, но для упрощения текущая реализация
 * возвращает первое сматчившееся нарушение в порядке: required → allowedValues → valuePattern.
 * <p>
 * Дополнительно поддерживается фильтр по {@link SpanRecord#kind()}: правило применяется только к
 * span'ам с указанным {@code applicableKind} (если задан).
 */
public final class AttributeRule implements LintRule {

    private final String id;
    private final String description;
    private final LintSeverity severity;
    private final String attributeKey;
    private final boolean required;
    private final Set<String> allowedValues;
    private final Pattern valuePattern;
    private final String applicableKind;

    private AttributeRule(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.description = Objects.requireNonNullElse(builder.description, "");
        this.severity = Objects.requireNonNullElse(builder.severity, LintSeverity.ERROR);
        this.attributeKey = Objects.requireNonNull(builder.attributeKey, "attributeKey");
        this.required = builder.required;
        this.allowedValues = builder.allowedValues == null ? Set.of() : Set.copyOf(builder.allowedValues);
        this.valuePattern = builder.valuePattern;
        this.applicableKind = builder.applicableKind;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Optional<LintViolation> apply(SpanRecord span) {
        if (applicableKind != null && !applicableKind.equalsIgnoreCase(span.kind())) {
            return Optional.empty();
        }
        String value = span.resolveAttribute(attributeKey);

        if (required && (value == null || value.isEmpty())) {
            return Optional.of(violation(span, "обязательный атрибут '" + attributeKey + "' отсутствует"));
        }
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        if (!allowedValues.isEmpty() && !allowedValues.contains(value)) {
            return Optional.of(violation(span,
                    "значение '" + value + "' атрибута '" + attributeKey + "' не входит в допустимый набор " + allowedValues));
        }
        if (valuePattern != null && !valuePattern.matcher(value).matches()) {
            return Optional.of(violation(span,
                    "значение '" + value + "' атрибута '" + attributeKey + "' не соответствует шаблону '" + valuePattern.pattern() + "'"));
        }
        return Optional.empty();
    }

    private LintViolation violation(SpanRecord span, String message) {
        return new LintViolation(id, severity, span.name(), message);
    }

    public static Builder builder(String id, String attributeKey) {
        return new Builder(id, attributeKey);
    }

    public static final class Builder {
        private final String id;
        private final String attributeKey;
        private String description;
        private LintSeverity severity;
        private boolean required;
        private Set<String> allowedValues;
        private Pattern valuePattern;
        private String applicableKind;

        private Builder(String id, String attributeKey) {
            this.id = id;
            this.attributeKey = attributeKey;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder severity(LintSeverity severity) {
            this.severity = severity;
            return this;
        }

        public Builder required() {
            this.required = true;
            return this;
        }

        public Builder allowedValues(Set<String> allowedValues) {
            this.allowedValues = allowedValues;
            return this;
        }

        public Builder valuePattern(String regex) {
            this.valuePattern = Pattern.compile(regex);
            return this;
        }

        public Builder applicableKind(String kind) {
            this.applicableKind = kind;
            return this;
        }

        public AttributeRule build() {
            return new AttributeRule(this);
        }
    }
}
