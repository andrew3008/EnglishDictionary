package space.br1440.platform.tracing.core.semconv.policy;

import io.opentelemetry.api.common.Attributes;

import space.br1440.platform.tracing.api.semconv.SemconvViolation;

import java.util.List;

/**
 * Результат {@link AttributePolicy#validateAndNormalize}: ЕДИНЫЙ нормализованный snapshot
 * атрибутов (после применения safe-defaults в режиме WARN) и список обнаруженных нарушений.
 * <p>
 * Зачем единый snapshot: и low-cardinality имя span'а ({@code PlatformSpanNameBuilder}), и сам
 * {@code SpanBuilder.setAllAttributes(...)} должны строиться из ОДНОГО набора атрибутов. Иначе
 * имя считалось бы по атрибутам ДО safe-defaults, а span получал бы атрибуты ПОСЛЕ — рассинхрон.
 *
 * @param attributes нормализованные атрибуты (для имени и для SpanBuilder)
 * @param violations список нарушений (пустой в режиме DISABLED и при отсутствии нарушений)
 */
public record ValidatedAttributes(Attributes attributes, List<SemconvViolation> violations) {

    public ValidatedAttributes {
        violations = List.copyOf(violations);
    }
}
