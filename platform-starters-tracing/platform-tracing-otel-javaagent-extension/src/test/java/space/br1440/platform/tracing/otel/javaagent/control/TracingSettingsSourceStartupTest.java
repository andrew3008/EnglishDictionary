package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

class TracingSettingsSourceStartupTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID =
            AgentInstanceId.parse("00000000-0000-0000-0000-000000000001");

    @Test
    void processErrorStartsError() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "ERROR"
        )), AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
        assertThat(source.current().agentInstanceId()).isEqualTo(AGENT_INSTANCE_ID);
    }

    @Test
    void processDisabledStartsDisabled() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "DISABLED"
        )), AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.DISABLED);
    }

    @Test
    void absentLevelStartsWarning() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(
                config(Map.of()),
                AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.WARNING);
    }

    @Test
    void leftoverPlatformEnvironmentDoesNotDowngradeError() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "ERROR",
                "platform.environment",
                "production"
        )), AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
    }

    @Test
    void processDisabledCanBeEnabledLaterWithoutLifetimeBan() {
        // given
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "DISABLED"
        )), AGENT_INSTANCE_ID);

        // when
        SettingsUpdateStatus outcome = AcceptedPolicyMutationAccess.applyValidation(
                source, SpanAttributeValidationLevel.ERROR);

        // then
        assertThat(outcome).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
    }

    @Test
    void unknownLevelIsRejectedBeforeSettingsAreCreated() {
        // given/when/then
        assertThatThrownBy(() -> TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "STRICT"
        )), AGENT_INSTANCE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.tracing.javaagent.span-attributes.validation.level");
    }

    @Test
    void isolatedDisabledStateStartsDisabled() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.DISABLED);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.DISABLED);
    }

    @Test
    void isolatedErrorStateIsNotBlockedByRemovedEnvironmentGate() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.ERROR);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
    }

    @Test
    void isolatedSourceRejectsDirectStoreMutation() {
        // given
        TracingSettingsSource source = TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.DISABLED);
        TracingSettingsRevision initial = source.current();

        // when
        Throwable failure = catchThrowable(() -> AcceptedPolicyMutationAccess.applyValidation(
                source, SpanAttributeValidationLevel.ERROR));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot commit tracing settings to an unbound store");
        assertThat(source.current().acceptedSequence()).isZero();
        assertThat(source.current().policy()).isEqualTo(initial.policy());
    }

    private static ExtensionConfig config(Map<String, String> values) {
        return new ExtensionConfig(new MapConfigProperties(values));
    }

    private static final class MapConfigProperties implements ConfigProperties {
        private final Map<String, String> data;

        private MapConfigProperties(Map<String, String> data) {
            this.data = new HashMap<>(data);
        }

        @Override
        public String getString(String name) {
            return data.get(name);
        }

        @Override
        public Boolean getBoolean(String name) {
            String value = data.get(name);
            return value == null ? null : Boolean.parseBoolean(value);
        }

        @Override
        public Integer getInt(String name) {
            return null;
        }

        @Override
        public Long getLong(String name) {
            return null;
        }

        @Override
        public Double getDouble(String name) {
            return null;
        }

        @Override
        public Duration getDuration(String name) {
            return null;
        }

        @Override
        public List<String> getList(String name) {
            return null;
        }

        @Override
        public Map<String, String> getMap(String name) {
            return null;
        }
    }
}
