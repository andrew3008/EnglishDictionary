package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundDefaults;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class ValidatingSpanProcessorTest {

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    private static ValidatingSpanProcessor warnLevel() {
        return new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.WARNING));
    }

    private static ValidatingSpanProcessor errorLevel() {
        return new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.ERROR));
    }

    @Test
    void sets_platform_validation_missing_when_span_attributes_absent() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), Resource.empty())) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            // when
            span.end();

            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            String missing = data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"));
            // then
            assertThat(missing)
                    .contains(PlatformAttributes.PLATFORM_TYPE)
                    .contains(PlatformAttributes.PLATFORM_RESULT);
        }
    }

    @Test
    void missing_required_attribute_log_contains_count_and_fixed_key_name() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), Resource.empty())) {
            // when
            h.tracer("test").spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP")
                    .startSpan()
                    .end();

            CapturedLogEvent event = capture.events().stream()
                    .filter(candidate -> "REQUIRED_SPAN_ATTRIBUTES_MISSING_WARN".equals(candidate.eventName()))
                    .findFirst()
                    .orElseThrow();

            // then
            assertThat(event.fields())
                    .containsEntry("count", "1")
                    .containsEntry("missingKeys", PlatformAttributes.PLATFORM_RESULT);
        }
    }

    @Test
    void does_not_record_violation_when_span_type_and_result_attributes_are_set() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), Resource.empty())) {
            h.tracer("test").spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                    // when
                    .startSpan().end();

            Attributes attrs = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            // then
            assertThat(attrs.get(AttributeKey.stringKey("platform.validation.missing"))).isNull();
        }
    }

    @Test
    void throttling_suppresses_repeated_warn_diagnostics() {
        // given
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(
                () -> 1000L,
                TracingSettingsSourceTestFixtures.boundValidation(
                        AgentInstanceId.random(),
                        SpanAttributeValidationLevel.WARNING
                )
        );

        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            for (int i = 0; i < 1000; i++) {
                // when
                tracer.spanBuilder("op").startSpan().end();
            }

            // then
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1000);
            for (SpanData data : h.exporter().getFinishedSpanItems()) {
                assertThat(data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"))).isNotBlank();
            }
        }

        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.VALID_MISSING_ATTRIBUTES)
                .filteredOn(event -> event.level() == AgentLogLevel.WARN)
                .hasSize(1);
        assertThat(validator.getValidationViolationsTotal()).isEqualTo(1000L);
    }

    @Test
    void warn_and_error_diagnostics_have_independent_throttling() {
        // given
        TracingSettingsSource access = boundDefaults(AgentInstanceId.random());
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(() -> 1000L, access);
        SettingsUpdateStatus updateStatus;

        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            // when
            h.tracer("test").spanBuilder("warn").startSpan().end();
            updateStatus = AcceptedPolicyMutationAccess.applyValidation(access, SpanAttributeValidationLevel.ERROR);
            h.tracer("test").spanBuilder("error").startSpan().end();
        }

        // then
        assertThat(updateStatus).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.VALID_MISSING_ATTRIBUTES)
                .filteredOn(event -> event.level() == AgentLogLevel.WARN)
                .hasSize(1);
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.VALID_MISSING_ATTRIBUTES)
                .filteredOn(event -> event.level() == AgentLogLevel.ERROR)
                .hasSize(1);
        assertThat(validator.getValidationViolationsTotal()).isEqualTo(2L);
    }

    @Test
    void error_level_records_violation_without_interrupting_span_end() {
        // given
        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            // when
            tracer.spanBuilder("op").startSpan().end();

            // then
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        }
    }

    @Test
    void strictValidationDoesNotUseResourceAttributeAsTraceTypeFallback() {
        // given
        Resource resourceWithType = Resource.create(
                Attributes.of(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE), "http_server"));

        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, resourceWithType)) {
            Tracer tracer = h.tracer("test");
            io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success");

            // when
            builder.startSpan().end();

            String missing = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("platform.validation.missing"));
            // then
            assertThat(missing).isEqualTo(PlatformAttributes.PLATFORM_TYPE);
            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        }
    }

    @Test
    void strictValidationDoesNotUseResourceAttributeAsTraceResultFallback() {
        // given
        Resource resourceWithResult = Resource.create(
                Attributes.of(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT), "success"));

        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, resourceWithResult)) {
            Tracer tracer = h.tracer("test");
            io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server");

            // when
            builder.startSpan().end();

            String missing = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("platform.validation.missing"));
            // then
            assertThat(missing).isEqualTo(PlatformAttributes.PLATFORM_RESULT);
            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        }
    }

    @Test
    void strictValidationAcceptsTraceTypeAndTraceResultSpanAttributes() {
        // given
        ValidatingSpanProcessor validator = errorLevel();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(validator, Resource.empty())) {
            Tracer tracer = h.tracer("test");
            tracer.spanBuilder("op")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                    // when
                    .startSpan().end();

            Attributes attrs = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            // then
            assertThat(attrs.get(AttributeKey.stringKey("platform.validation.missing"))).isNull();
        }
    }

    @Test
    void warnLevelValidationDoesNotThrowWhenRequiredSpanAttributesExistOnlyOnResource() {
        // given
        Resource resourceWithBoth = Resource.create(Attributes.builder()
                .put(PlatformAttributes.PLATFORM_TYPE, "http_server")
                .put(PlatformAttributes.PLATFORM_RESULT, "success")
                .build());

        try (SpanProcessorHarness h = SpanProcessorHarness.of(warnLevel(), resourceWithBoth)) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            // when
            span.end();

            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            String missing = data.getAttributes().get(AttributeKey.stringKey("platform.validation.missing"));
            // then
            assertThat(missing)
                    .contains(PlatformAttributes.PLATFORM_TYPE)
                    .contains(PlatformAttributes.PLATFORM_RESULT);
        }
    }
}
