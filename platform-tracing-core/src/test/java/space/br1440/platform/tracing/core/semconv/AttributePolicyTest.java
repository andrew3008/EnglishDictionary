package space.br1440.platform.tracing.core.semconv;

import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

class AttributePolicyTest {

    /** Перехватывающая реализация метрик для проверок. */
    private static final class CapturingMetrics implements SemconvMetrics {
        final List<String> violations = new ArrayList<>();
        final List<String> unsafe = new ArrayList<>();

        @Override
        public void violation(String ruleId, String builder) {
            violations.add(ruleId + "|" + builder);
        }

        @Override
        public void unsafeAttribute(String keyClass) {
            unsafe.add(keyClass);
        }
    }

    @Test
    void warn_приОтсутствииRequired_неБросает_подставляетSafeDefault_иПишетМетрику() {
        CapturingMetrics metrics = new CapturingMetrics();
        AttributePolicy policy = new AttributePolicy(ValidationMode.WARN, false, metrics);

        ValidatedAttributes result = policy.validateAndNormalize(
                SpanCategory.INTERNAL, Attributes.empty(), "InternalSpanBuilder");

        // platform.trace.type обязателен для INTERNAL -> safe-default подставлен значением категории.
        assertThat(result.attributes().get(SemconvKeys.PLATFORM_TYPE)).isEqualTo("internal");
        assertThat(result.violations())
                .extracting(v -> v.ruleId())
                .contains("REQUIRED_MISSING");
        assertThat(metrics.violations).contains("REQUIRED_MISSING|InternalSpanBuilder");
    }

    @Test
    void strict_приОтсутствииRequired_бросаетSemconvViolationException() {
        AttributePolicy policy = new AttributePolicy(ValidationMode.STRICT, false, SemconvMetrics.NOOP);

        assertThatExceptionOfType(SemconvViolationException.class)
                .isThrownBy(() -> policy.validateAndNormalize(
                        SpanCategory.INTERNAL, Attributes.empty(), "InternalSpanBuilder"));
    }

    @Test
    void disabled_возвращаетАтрибутыAsIs_безДефолтовЛоговМетрик() {
        CapturingMetrics metrics = new CapturingMetrics();
        AttributePolicy policy = new AttributePolicy(ValidationMode.DISABLED, false, metrics);

        ValidatedAttributes result = policy.validateAndNormalize(
                SpanCategory.INTERNAL, Attributes.empty(), "InternalSpanBuilder");

        assertThat(result.attributes().isEmpty()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(metrics.violations).isEmpty();
    }

    @Test
    void warn_приЗапрещённомАтрибуте_фиксируетForbiddenНарушение() {
        CapturingMetrics metrics = new CapturingMetrics();
        AttributePolicy policy = new AttributePolicy(ValidationMode.WARN, false, metrics);

        Attributes attrs = Attributes.builder()
                .put(SemconvKeys.PLATFORM_TYPE, "internal")
                .put(SemconvKeys.HTTP_REQUEST_METHOD, "GET")   // запрещён для INTERNAL
                .build();

        ValidatedAttributes result = policy.validateAndNormalize(
                SpanCategory.INTERNAL, attrs, "InternalSpanBuilder");

        assertThat(result.violations())
                .extracting(v -> v.ruleId())
                .contains("ATTR_FORBIDDEN");
        assertThat(metrics.violations).contains("ATTR_FORBIDDEN|InternalSpanBuilder");
    }

    @Test
    void warn_dbBezDbSystem_фиксируетRequiredAnyOf_аСНим_проходит() {
        AttributePolicy policy = new AttributePolicy(ValidationMode.WARN, false, SemconvMetrics.NOOP);

        Attributes without = Attributes.builder()
                .put(SemconvKeys.PLATFORM_TYPE, "database")
                .build();
        ValidatedAttributes missing = policy.validateAndNormalize(
                SpanCategory.DATABASE, without, "DatabaseSpanBuilder");
        assertThat(missing.violations())
                .extracting(v -> v.ruleId())
                .contains("REQUIRED_ANY_OF_MISSING");

        Attributes withLegacy = Attributes.builder()
                .put(SemconvKeys.PLATFORM_TYPE, "database")
                .put(SemconvKeys.DB_SYSTEM_LEGACY, "postgresql")
                .build();
        ValidatedAttributes ok = policy.validateAndNormalize(
                SpanCategory.DATABASE, withLegacy, "DatabaseSpanBuilder");
        assertThat(ok.violations())
                .extracting(v -> v.ruleId())
                .doesNotContain("REQUIRED_ANY_OF_MISSING");
    }

    @Test
    void unsafeAttribute_классифицируетKnownUnknownRejected_иПишетМетрику() {
        CapturingMetrics metrics = new CapturingMetrics();

        AttributePolicy allowed = new AttributePolicy(ValidationMode.WARN, true, metrics);
        assertThat(allowed.auditUnsafeAttribute("platform.trace.type"))
                .isEqualTo(AttributePolicy.UnsafeKeyClass.KNOWN);
        assertThat(allowed.auditUnsafeAttribute("custom.vendor.flag"))
                .isEqualTo(AttributePolicy.UnsafeKeyClass.UNKNOWN);

        AttributePolicy forbidden = new AttributePolicy(ValidationMode.WARN, false, metrics);
        assertThat(forbidden.auditUnsafeAttribute("custom.vendor.flag"))
                .isEqualTo(AttributePolicy.UnsafeKeyClass.REJECTED);

        assertThat(metrics.unsafe).containsExactly("known", "unknown", "rejected");
    }

    @Test
    void disabled_неОтключаетВыполнение_другихРежимов_конструкторПоУмолчаниюWarn() {
        assertThatNoException().isThrownBy(() -> new AttributePolicy().validateAndNormalize(
                SpanCategory.INTERNAL, Attributes.empty(), "default"));
        assertThat(new AttributePolicy().mode()).isEqualTo(ValidationMode.WARN);
    }
}
