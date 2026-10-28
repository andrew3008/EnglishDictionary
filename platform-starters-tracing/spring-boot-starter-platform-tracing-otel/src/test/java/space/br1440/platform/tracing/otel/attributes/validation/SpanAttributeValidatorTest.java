package space.br1440.platform.tracing.otel.attributes.validation;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeViolation;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeViolationException;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.common.Attributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SpanAttributeValidatorTest {

    private static final class CapturingMetrics implements SpanAttributeValidationMetrics {
        final List<String> violations = new ArrayList<>();

        @Override
        public void violation(String ruleId, String builderName) {
            violations.add(ruleId + "|" + builderName);
        }
    }

    @Test
    void warn_whenRequiredAbsent_doesNotThrow_appliesSafeDefault_andWritesMetric() {
        // given
        CapturingMetrics metrics = new CapturingMetrics();
        SpanAttributeValidator validator = new SpanAttributeValidator(SpanAttributeValidationLevel.WARNING, metrics);

        // when
        ValidatedAttributes result = validator.validateAndNormalize(
                SpanCategory.INTERNAL, Attributes.empty(), "InternalSpanBuilder");

        // then
        assertThat(result.attributes().get(SemconvKeys.PLATFORM_TYPE)).isEqualTo("internal");
        assertThat(result.violations())
                .extracting(SpanAttributeViolation::ruleId)
                .contains("REQUIRED_MISSING");
        assertThat(metrics.violations).contains("REQUIRED_MISSING|InternalSpanBuilder");
    }

    @Test
    void strict_whenRequiredAbsent_throwsSpanAttributeViolationException() {
        // given
        SpanAttributeValidator validator = new SpanAttributeValidator(
                SpanAttributeValidationLevel.ERROR, SpanAttributeValidationMetrics.NOOP);

        // when / then
        assertThatExceptionOfType(SpanAttributeViolationException.class)
                .isThrownBy(() -> validator.validateAndNormalize(
                        SpanCategory.INTERNAL, Attributes.empty(), "InternalSpanBuilder"));
    }

    @Test
    void disabled_returnsAttributesAsIs_withoutDefaultsLogsMetrics() {
        // given
        CapturingMetrics metrics = new CapturingMetrics();
        SpanAttributeValidator validator = new SpanAttributeValidator(SpanAttributeValidationLevel.DISABLED, metrics);

        // when
        ValidatedAttributes result = validator.validateAndNormalize(
                SpanCategory.INTERNAL, Attributes.empty(), "InternalSpanBuilder");

        // then
        assertThat(result.attributes().isEmpty()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(metrics.violations).isEmpty();
    }

    @Test
    void warn_onForbiddenAttribute_recordsForbiddenViolation() {
        // given
        CapturingMetrics metrics = new CapturingMetrics();
        SpanAttributeValidator validator = new SpanAttributeValidator(SpanAttributeValidationLevel.WARNING, metrics);
        Attributes attrs = Attributes.builder()
                .put(SemconvKeys.PLATFORM_TYPE, "internal")
                .put(SemconvKeys.HTTP_REQUEST_METHOD, "GET")
                .build();

        // when
        ValidatedAttributes result = validator.validateAndNormalize(
                SpanCategory.INTERNAL, attrs, "InternalSpanBuilder");

        // then
        assertThat(result.violations())
                .extracting(SpanAttributeViolation::ruleId)
                .contains("ATTR_FORBIDDEN");
        assertThat(metrics.violations).contains("ATTR_FORBIDDEN|InternalSpanBuilder");
    }

    @Test
    void warn_dbWithoutDbSystem_recordsRequiredAnyOf_andPassesWithIt() {
        // given
        SpanAttributeValidator validator = new SpanAttributeValidator(
                SpanAttributeValidationLevel.WARNING, SpanAttributeValidationMetrics.NOOP);

        // when
        ValidatedAttributes missing = validator.validateAndNormalize(
                SpanCategory.DATABASE,
                Attributes.builder().put(SemconvKeys.PLATFORM_TYPE, "database").build(),
                "DatabaseSpanBuilder");
        ValidatedAttributes ok = validator.validateAndNormalize(
                SpanCategory.DATABASE,
                Attributes.builder()
                        .put(SemconvKeys.PLATFORM_TYPE, "database")
                        .put(SemconvKeys.DB_SYSTEM_LEGACY, "postgresql")
                        .build(),
                "DatabaseSpanBuilder");

        // then
        assertThat(missing.violations())
                .extracting(SpanAttributeViolation::ruleId)
                .contains("REQUIRED_ANY_OF_MISSING");

        assertThat(ok.violations())
                .extracting(SpanAttributeViolation::ruleId)
                .doesNotContain("REQUIRED_ANY_OF_MISSING");
    }

    @Test
    void defaultConstructor_isWarn() {
        // given / when / then
        assertThatNoException().isThrownBy(() -> new SpanAttributeValidator().validateAndNormalize(
                SpanCategory.INTERNAL,
                Attributes.empty(),
                "default"
        ));

        assertThat(new SpanAttributeValidator().level()).isEqualTo(SpanAttributeValidationLevel.WARNING);
    }

    @Test
    void databaseAllowlist_acceptsResponseStatusCode() {
        // given
        SpanAttributeValidator validator = new SpanAttributeValidator(
                SpanAttributeValidationLevel.WARNING, SpanAttributeValidationMetrics.NOOP);
        Attributes attributes = Attributes.builder()
                .put(SemconvKeys.PLATFORM_TYPE, "database")
                .put(SemconvKeys.DB_SYSTEM_NAME, "postgresql")
                .put(SemconvKeys.DB_RESPONSE_STATUS_CODE, "23505")
                .build();

        // when
        ValidatedAttributes result = validator.validateAndNormalize(
                SpanCategory.DATABASE, attributes, "DatabaseSpanBuilder");

        // then
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void httpClientAllowlist_rejectsResponseStatusCode() {
        // given
        SpanAttributeValidator validator = new SpanAttributeValidator(
                SpanAttributeValidationLevel.WARNING, SpanAttributeValidationMetrics.NOOP);
        Attributes attributes = Attributes.builder()
                .put(SemconvKeys.PLATFORM_TYPE, "http_client")
                .put(SemconvKeys.HTTP_REQUEST_METHOD, "GET")
                .put(SemconvKeys.DB_RESPONSE_STATUS_CODE, "23505")
                .build();

        // when
        ValidatedAttributes result = validator.validateAndNormalize(
                SpanCategory.HTTP_CLIENT, attributes, "HttpClientSpanBuilder");

        // then
        assertThat(result.violations())
                .filteredOn(violation -> SemconvKeys.DB_RESPONSE_STATUS_CODE.getKey().equals(violation.attributeKey()))
                .extracting(SpanAttributeViolation::ruleId)
                .containsExactly("ATTR_NOT_ALLOWED");
    }
}
