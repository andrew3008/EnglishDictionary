package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_PROPAGATION_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS;

import java.util.Map;
import java.util.regex.PatternSyntaxException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

class BaggageExtensionConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS, BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS})
    void invalid_pattern_fails_configuration_with_property_name(String propertyName) {
        // given
        Map<String, String> properties = Map.of(propertyName, "[");

        // when
        ConfigurationPropertyException failure = catchThrowableOfType(
                ConfigurationPropertyException.class, () -> config(properties));

        // then
        assertThat(failure.propertyName()).isEqualTo(propertyName);
        assertThat(failure.failure())
                .hasMessage(propertyName + " contains an invalid regular expression")
                .hasCauseInstanceOf(PatternSyntaxException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"platform.correlation.id", "Platform.Correlation.Id", "PLATFORM.CORRELATION.ID"})
    void outbound_allowlist_rejects_reserved_key_in_any_case(String key) {
        // given
        Map<String, String> properties = Map.of(BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS, "traffic_source," + key);

        // when
        ConfigurationPropertyException failure = catchThrowableOfType(
                ConfigurationPropertyException.class, () -> config(properties));

        // then
        assertThat(failure.propertyName()).isEqualTo(BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS);
        assertThat(failure.failure()).hasMessage(BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS
                + " must not contain reserved key platform.correlation.id");
    }

    @Test
    void invalid_outbound_policy_is_rejected_even_when_propagation_is_disabled() {
        // given
        Map<String, String> properties = Map.of(
                BAGGAGE_PROPAGATION_ENABLED, "false",
                BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS, "[");

        // when
        ConfigurationPropertyException failure = catchThrowableOfType(
                ConfigurationPropertyException.class, () -> config(properties));

        // then
        assertThat(failure.propertyName()).isEqualTo(BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS);
    }

    @Test
    void defaults_allow_correlation_only_for_span_attributes() {
        // given
        BaggageExtensionConfig config = config(Map.of());

        // when
        BaggageKeyPolicy outbound = config.outboundHeaderPolicy();
        BaggageKeyPolicy spanAttributes = config.spanAttributesPolicy();

        // then
        assertThat(config.propagationEnabled()).isTrue();
        assertThat(outbound.allows("traffic_source")).isTrue();
        assertThat(outbound.allows("tenant_class")).isTrue();
        assertThat(outbound.allows("platform.correlation.id")).isFalse();
        assertThat(spanAttributes.allows("platform.correlation.id")).isTrue();
        assertThat(spanAttributes.allows("Platform.Correlation.Id")).isTrue();
        assertThat(outbound).isSameAs(config.outboundHeaderPolicy());
        assertThat(spanAttributes).isSameAs(config.spanAttributesPolicy());
    }

    @Test
    void policies_are_independent_and_compile_case_insensitive_deny_patterns() {
        // given
        BaggageExtensionConfig config = config(Map.of(
                BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS, "Tenant_Class,outbound_only,access_token",
                BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS, "TOKEN",
                BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS, "Tenant_Class,span_only,outbound_only",
                BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS, "OUTBOUND"));

        // when
        BaggageKeyPolicy outbound = config.outboundHeaderPolicy();
        BaggageKeyPolicy spanAttributes = config.spanAttributesPolicy();

        // then
        assertThat(outbound.allows("TENANT_CLASS")).isTrue();
        assertThat(outbound.allows("outbound_only")).isTrue();
        assertThat(outbound.allows("access_token")).isFalse();
        assertThat(outbound.allows("span_only")).isFalse();
        assertThat(spanAttributes.allows("tenant_class")).isTrue();
        assertThat(spanAttributes.allows("span_only")).isTrue();
        assertThat(spanAttributes.allows("outbound_only")).isFalse();
    }

    @Test
    void default_deny_patterns_override_explicit_allowlists() {
        // given
        BaggageExtensionConfig config = config(Map.of(
                BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS, "traffic_source,password_x,api_secret,access_token",
                BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS, "traffic_source,password_x,api_secret,access_token"));

        // when
        BaggageKeyPolicy outbound = config.outboundHeaderPolicy();
        BaggageKeyPolicy spanAttributes = config.spanAttributesPolicy();

        // then
        assertThat(outbound.allows("traffic_source")).isTrue();
        assertThat(spanAttributes.allows("traffic_source")).isTrue();
        for (String key : new String[] {"password_x", "api_secret", "access_token"}) {
            assertThat(outbound.allows(key)).as(key).isFalse();
            assertThat(spanAttributes.allows(key)).as(key).isFalse();
        }
    }

    @Test
    void explicit_empty_allowlists_disable_both_policies() {
        // given
        BaggageExtensionConfig config = config(Map.of(
                BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS, "",
                BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS, ""));

        // when
        BaggageKeyPolicy outbound = config.outboundHeaderPolicy();
        BaggageKeyPolicy spanAttributes = config.spanAttributesPolicy();

        // then
        assertThat(outbound.isEmpty()).isTrue();
        assertThat(spanAttributes.isEmpty()).isTrue();
        assertThat(outbound.allows("traffic_source")).isFalse();
        assertThat(spanAttributes.allows("platform.correlation.id")).isFalse();
    }

    private static BaggageExtensionConfig config(Map<String, String> properties) {
        return new BaggageExtensionConfig(new ExtensionConfigReader(DefaultConfigProperties.createFromMap(properties)));
    }
}
