package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SamplingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

class AgentLogFieldsBuilderTest {

    @Test
    void snapshot_uses_catalog_order_and_canonical_typed_values() {
        // given
        AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);
        fields.putString(AgentLogField.PROFILE, "production");
        fields.putLong(AgentLogField.PROTOCOL_VERSION, 7L);
        fields.putEnum(AgentLogField.LIFECYCLE, Lifecycle.ACTIVE);
        fields.putString(AgentLogField.EXTENSION_VERSION, "1.2.3");

        // when
        AgentLogFieldsBuilder.Snapshot snapshot = fields.snapshot();

        // then
        assertThat(snapshot.fields()).containsExactly(
                entry("extensionVersion", "1.2.3"),
                entry("lifecycle", "ACTIVE"),
                entry("profile", "production"),
                entry("protocolVersion", "7")
        );
        assertThat(snapshot.throwableDigest()).isNull();
    }

    @Test
    void snapshot_is_immutable_and_detached_from_builder() {
        // given
        AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);
        fields.putString(AgentLogField.PROFILE, "production");
        AgentLogFieldsBuilder.Snapshot snapshot = fields.snapshot();

        // when
        fields.putString(AgentLogField.EXTENSION_VERSION, "1.2.3");

        // then
        assertThat(snapshot.fields()).containsExactly(entry("profile", "production"));
        assertThatThrownBy(() -> snapshot.fields().put("extensionVersion", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void disallowed_field_is_rejected_before_value_is_stored() {
        // given
        AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);

        // when
        // then
        assertThatThrownBy(() -> fields.putString(AgentLogField.NAME, "provider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field NAME is not allowed for event EXTENSION_INSTALLED");
        assertThat(fields.snapshot().fields()).isEmpty();
    }

    @Test
    void duplicate_field_is_rejected() {
        // given
        AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);
        fields.putString(AgentLogField.PROFILE, "production");

        // when
        // then
        assertThatThrownBy(() -> fields.putString(AgentLogField.PROFILE, "staging"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field PROFILE must not be assigned more than once");
        assertThat(fields.snapshot().fields()).containsExactly(entry("profile", "production"));
    }

    @Test
    void null_catalog_inputs_are_rejected() {
        // given
        AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);

        // when
        // then
        assertThatNullPointerException().isThrownBy(() -> new AgentLogFieldsBuilder(null))
                .withMessage("eventType");
        assertThatNullPointerException().isThrownBy(() -> fields.putString(null, "production"))
                .withMessage("field");
        assertThatNullPointerException().isThrownBy(() -> fields.putString(AgentLogField.PROFILE, null))
                .withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> fields.putEnum(AgentLogField.LIFECYCLE, null))
                .withMessage("value");
    }

    @Test
    void value_kind_must_match_field_kind() {
        // given
        AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);

        // when
        // then
        assertThatThrownBy(() -> fields.putString(AgentLogField.PROTOCOL_VERSION, "7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field PROTOCOL_VERSION requires LONG values");
        assertThatThrownBy(() -> fields.putLong(AgentLogField.PROFILE, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field PROFILE requires STRING values");
        assertThatThrownBy(() -> fields.putEnum(AgentLogField.PROFILE, Lifecycle.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field PROFILE requires STRING values");
        assertThat(fields.snapshot().fields()).isEmpty();
    }

    @Test
    void blank_string_is_rejected_unless_catalog_explicitly_allows_it() {
        // given
        AgentLogFieldsBuilder regular = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);
        AgentLogFieldsBuilder blankAllowed = new AgentLogFieldsBuilder(
                BootstrapLogEvent.REMOTE_JMX_MUTATION_REGISTRATION_DENIED
        );

        // when
        blankAllowed.putString(AgentLogField.OFFENDING_ARGUMENTS, "");

        // then
        assertThatThrownBy(() -> regular.putString(AgentLogField.PROFILE, " \t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("String field value must not be blank");
        assertThat(blankAllowed.snapshot().fields()).containsExactly(entry("offendingArguments", ""));
    }

    @Test
    void string_value_is_sanitized_and_bounded_before_snapshot() {
        // given
        AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);
        String raw = "line\n" + "x".repeat(AgentLogRecord.MAX_ARGUMENT_CHARS);

        // when
        fields.putString(AgentLogField.PROFILE, raw);

        // then
        assertThat(fields.snapshot().fields().get("profile"))
                .hasSize(AgentLogRecord.MAX_ARGUMENT_CHARS)
                .startsWith("line?xxx")
                .doesNotContain("\n");
    }

    @Test
    void forbidden_cause_is_rejected() {
        // given
        AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);
        IllegalStateException cause = failureWithoutFrames();

        // when
        // then
        assertThatThrownBy(() -> fields.cause(cause))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cause is forbidden for event EXTENSION_INSTALLED");
        assertThat(fields.snapshot().throwableDigest()).isNull();
    }

    @Test
    void required_cause_must_be_assigned_exactly_once() {
        // given
        AgentLogFieldsBuilder missing = new AgentLogFieldsBuilder(BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED);
        AgentLogFieldsBuilder assigned = new AgentLogFieldsBuilder(BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED);
        IllegalStateException cause = failureWithoutFrames();
        assigned.cause(cause);

        // when
        AgentLogFieldsBuilder.Snapshot snapshot = assigned.snapshot();

        // then
        assertThatThrownBy(missing::snapshot)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cause is required for event BOOTSTRAP_PHASE_FAILED");
        assertThatThrownBy(() -> assigned.cause(failureWithoutFrames()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cause must not be assigned more than once");
        assertThat(snapshot.throwableDigest()).isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void optional_cause_may_be_omitted_or_rendered() {
        // given
        AgentLogFieldsBuilder omitted = new AgentLogFieldsBuilder(SamplingLogEvent.SAMPLER_FAILED_CLOSED_TO_DROP);
        AgentLogFieldsBuilder assigned = new AgentLogFieldsBuilder(SamplingLogEvent.SAMPLER_FAILED_CLOSED_TO_DROP);
        assigned.cause(failureWithoutFrames());

        // when
        AgentLogFieldsBuilder.Snapshot omittedSnapshot = omitted.snapshot();
        AgentLogFieldsBuilder.Snapshot assignedSnapshot = assigned.snapshot();

        // then
        assertThat(omittedSnapshot.throwableDigest()).isNull();
        assertThat(assignedSnapshot.throwableDigest()).isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void null_cause_is_rejected_before_cause_policy_is_evaluated() {
        // given
        AgentLogFieldsBuilder forbidden = new AgentLogFieldsBuilder(BootstrapLogEvent.EXTENSION_INSTALLED);
        AgentLogFieldsBuilder optional = new AgentLogFieldsBuilder(SamplingLogEvent.SAMPLER_FAILED_CLOSED_TO_DROP);

        // when
        // then
        assertThatNullPointerException().isThrownBy(() -> forbidden.cause(null))
                .withMessage("cause");
        assertThatNullPointerException().isThrownBy(() -> optional.cause(null))
                .withMessage("cause");
    }

    private static IllegalStateException failureWithoutFrames() {
        IllegalStateException failure = new IllegalStateException("must not be rendered");
        failure.setStackTrace(new StackTraceElement[0]);
        return failure;
    }

    private enum Lifecycle {
        ACTIVE;

        @Override
        public String toString() {
            return "display-only";
        }
    }
}
