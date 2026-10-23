package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class TypedAgentLoggingPipelineTest {

    private static final String FALLBACK_MESSAGE = "Platform Agent log event could not be encoded safely";

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    private PlatformAgentLogger logger;

    @BeforeEach
    void createLogger() {
        logger = PlatformAgentLogger.get(TypedAgentLoggingPipelineTest.class);
    }

    @Test
    void normal_typed_emission_uses_catalog_metadata_and_canonical_fields() {
        // given
        capture.applyLevel(AgentLogLevel.INFO);

        // when
        logger.log(BootstrapLogEvent.EXTENSION_INSTALLED, fields -> fields
                .putString(AgentLogField.EXTENSION_VERSION, "1.2.3")
                .putEnum(AgentLogField.LIFECYCLE, Lifecycle.ACTIVE)
                .putString(AgentLogField.PROFILE, "production")
                .putLong(AgentLogField.PROTOCOL_VERSION, 7L));

        // then
        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.level()).isEqualTo(AgentLogLevel.INFO);
            assertThat(event.eventCode()).isEqualTo(AgentEventCode.BOOT_INSTALLED);
            assertThat(event.eventName()).isEqualTo("EXTENSION_INSTALLED");
            assertThat(event.originalEventName()).isNull();
            assertThat(event.message()).isEqualTo("Platform tracing extension installed");
            assertThat(event.logger()).isEqualTo(TypedAgentLoggingPipelineTest.class.getName());
            assertThat(event.fields()).containsExactly(
                    entry("extensionVersion", "1.2.3"),
                    entry("lifecycle", "ACTIVE"),
                    entry("profile", "production"),
                    entry("protocolVersion", "7")
            );
            assertThat(event.throwableDigest()).isNull();
        });
    }

    @Test
    void filtered_event_does_not_invoke_fields_callback() {
        // given
        capture.applyLevel(AgentLogLevel.ERROR);
        AtomicInteger callbackCalls = new AtomicInteger();

        // when
        logger.log(BootstrapLogEvent.EXTENSION_INSTALLED, fields -> callbackCalls.incrementAndGet());

        // then
        assertThat(callbackCalls).hasValue(0);
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void out_of_allowlist_field_emits_one_fixed_fallback_without_caller_values() {
        // given
        AgentLogFieldsWriter writer = fields -> fields
                .putString(AgentLogField.PROFILE, "private-profile")
                .putString(AgentLogField.NAME, "private-name");

        // when
        EmitResult result = logger.emitAdmitted(BootstrapLogEvent.EXTENSION_INSTALLED, writer);

        // then
        assertInvariantFallback(result, AgentEventCode.BOOT_INSTALLED, "private-profile", "private-name");
    }

    @Test
    void duplicate_field_emits_one_fixed_fallback_without_caller_values() {
        // given
        AgentLogFieldsWriter writer = fields -> fields
                .putString(AgentLogField.PROFILE, "private-first")
                .putString(AgentLogField.PROFILE, "private-second");

        // when
        EmitResult result = logger.emitAdmitted(BootstrapLogEvent.EXTENSION_INSTALLED, writer);

        // then
        assertInvariantFallback(result, AgentEventCode.BOOT_INSTALLED, "private-first", "private-second");
    }

    @Test
    void null_field_value_emits_one_fixed_fallback_without_caller_values() {
        // given
        AgentLogFieldsWriter writer = fields -> fields.putString(AgentLogField.PROFILE, null);

        // when
        EmitResult result = logger.emitAdmitted(BootstrapLogEvent.EXTENSION_INSTALLED, writer);

        // then
        assertInvariantFallback(result, AgentEventCode.BOOT_INSTALLED);
    }

    @Test
    void blank_field_value_emits_one_fixed_fallback_without_caller_values() {
        // given
        AgentLogFieldsWriter writer = fields -> fields.putString(AgentLogField.PROFILE, " \t");

        // when
        EmitResult result = logger.emitAdmitted(BootstrapLogEvent.EXTENSION_INSTALLED, writer);

        // then
        assertInvariantFallback(result, AgentEventCode.BOOT_INSTALLED);
    }

    @Test
    void forbidden_cause_emits_one_fixed_fallback_without_caller_values() {
        // given
        IllegalStateException failure = failureWithoutFrames();
        AgentLogFieldsWriter writer = fields -> fields
                .putString(AgentLogField.PROFILE, "private-profile")
                .cause(failure);

        // when
        EmitResult result = logger.emitAdmitted(BootstrapLogEvent.EXTENSION_INSTALLED, writer);

        // then
        assertInvariantFallback(
                result,
                AgentEventCode.BOOT_INSTALLED,
                "private-profile",
                "private-forbidden-cause"
        );
    }

    @Test
    void missing_required_cause_emits_one_fixed_fallback_without_caller_values() {
        // given
        AgentLogFieldsWriter writer = fields -> fields.putString(AgentLogField.PHASE, "private-phase");

        // when
        EmitResult result = logger.emitAdmitted(BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED, writer);

        // then
        assertInvariantFallback(result, AgentEventCode.BOOT_CLASSIFIED_FAILURE, "private-phase");
    }

    @Test
    void throwing_callback_after_partial_writes_emits_one_fixed_fallback_without_caller_values() {
        // given
        AgentLogFieldsWriter writer = fields -> {
            fields.putString(AgentLogField.PROFILE, "private-profile");
            throw new IllegalStateException("private-callback-failure");
        };

        // when
        EmitResult result = logger.emitAdmitted(BootstrapLogEvent.EXTENSION_INSTALLED, writer);

        // then
        assertInvariantFallback(
                result,
                AgentEventCode.BOOT_INSTALLED,
                "private-profile",
                "private-callback-failure"
        );
    }

    @Test
    void recursive_callback_is_rejected_without_invoking_nested_callback() {
        // given
        AtomicInteger nestedCallbackCalls = new AtomicInteger();
        AtomicReference<EmitResult> nestedResult = new AtomicReference<>();

        // when
        EmitResult outerResult = logger.emitAdmitted(BootstrapLogEvent.EXTENSION_INSTALLED, fields -> {
            nestedResult.set(logger.emitAdmitted(BootstrapLogEvent.EXTENSION_INSTALLED, nestedFields -> {
                nestedCallbackCalls.incrementAndGet();
                nestedFields.putString(AgentLogField.PROFILE, "private-nested-profile");
            }));
            fields.putString(AgentLogField.PROFILE, "outer-profile");
        });

        // then
        assertThat(outerResult).isEqualTo(EmitResult.EMITTED);
        assertThat(nestedResult.get()).isEqualTo(EmitResult.DELIVERY_FAILED);
        assertThat(nestedCallbackCalls).hasValue(0);
        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.eventCode()).isEqualTo(AgentEventCode.BOOT_INSTALLED);
            assertThat(event.fields()).containsExactly(entry("profile", "outer-profile"));
        });
        assertThat(capture.rendered()).doesNotContain("private-nested-profile");
    }

    @Test
    void typed_rate_transition_emits_marker_before_problem_and_denied_callback_is_lazy() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicInteger deniedCallbackCalls = new AtomicInteger();
        RateLimitedLogger rateLimited = new RateLimitedLogger(
                logger,
                1,
                Duration.ofMinutes(1L),
                clock::get
        );
        EmitResult fast = rateLimited.logWithResult(
                SpanTimeoutLogEvent.SPAN_TIMEOUT_PER_TRACE_CAPACITY_REACHED,
                fields -> fields
                        .putLong(AgentLogField.REJECTED, 1L)
                        .putLong(AgentLogField.LIMIT, 10L)
        );

        // when
        EmitResult transition = rateLimited.logWithResult(
                SpanTimeoutLogEvent.SPAN_TIMEOUT_PER_TRACE_CAPACITY_REACHED,
                fields -> fields
                        .putLong(AgentLogField.REJECTED, 2L)
                        .putLong(AgentLogField.LIMIT, 10L)
        );
        EmitResult denied = rateLimited.logWithResult(
                SpanTimeoutLogEvent.SPAN_TIMEOUT_PER_TRACE_CAPACITY_REACHED,
                fields -> deniedCallbackCalls.incrementAndGet()
        );

        // then
        assertThat(fast).isEqualTo(EmitResult.EMITTED);
        assertThat(transition).isEqualTo(EmitResult.EMITTED);
        assertThat(denied).isEqualTo(EmitResult.RATE_LIMITED);
        assertThat(deniedCallbackCalls).hasValue(0);
        assertThat(capture.events()).extracting(CapturedLogEvent::eventCode).containsExactly(
                AgentEventCode.EXPORT_SPAN_TIMEOUT,
                AgentEventCode.RATE_THROTTLED,
                AgentEventCode.EXPORT_SPAN_TIMEOUT
        );
        assertThat(capture.events().get(1).fields()).containsExactly(entry("kind", "warnings"));
        assertThat(capture.events().get(2).fields()).containsExactly(
                entry("limit", "10"),
                entry("rejected", "2")
        );
    }

    private void assertInvariantFallback(EmitResult result,
                                         AgentEventCode originalEventCode,
                                         String... forbiddenFragments) {
        assertThat(result).isEqualTo(EmitResult.FALLBACK_EMITTED);
        String originalEventName = (originalEventCode == AgentEventCode.BOOT_CLASSIFIED_FAILURE)
                ? "BOOTSTRAP_PHASE_FAILED"
                : "EXTENSION_INSTALLED";
        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.eventCode()).isEqualTo(AgentEventCode.ENCODE_FALLBACK);
            assertThat(event.eventName()).isEqualTo("ENCODE_FALLBACK");
            assertThat(event.originalEventName()).isEqualTo(originalEventName);
            assertThat(event.message()).isEqualTo(FALLBACK_MESSAGE);
            assertThat(event.fields()).containsExactly(
                    entry("original_event_code", originalEventCode.code()),
                    entry("fallback_reason", FallbackReason.INVARIANT_VIOLATION.wireValue())
            );
            assertThat(event.throwableDigest()).isNull();
        });
        if (forbiddenFragments.length > 0) {
            assertThat(capture.rendered()).doesNotContain(forbiddenFragments);
        }
    }

    private static IllegalStateException failureWithoutFrames() {
        IllegalStateException failure = new IllegalStateException("private-forbidden-cause");
        failure.setStackTrace(new StackTraceElement[0]);
        return failure;
    }

    private enum Lifecycle {
        ACTIVE
    }
}
