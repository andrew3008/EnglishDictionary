package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerSettingsManifestBuilder;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;

class MalformedResponseAndManifestPayloadTest {

    private static final CompositeType WRONG_TYPE = wrongCompositeType();
    private static final String[] RESPONSE_KEYS = {
            "acceptedSequence", "attemptId", "messageCode", "outcome", "agentInstanceId", "violationCodes"
    };
    private static final CompositeType RESPONSE_TYPE = responseType();

    @Test
    void decode_response_null_throws() {
        assertThatThrownBy(() -> PlatformJmxResponse.decode(null))
                .as("null response CompositeData MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CompositeData");
    }

    @Test
    void decode_response_wrong_type_throws() throws OpenDataException {
        CompositeData wrong = new CompositeDataSupport(
                WRONG_TYPE,
                new String[]{"dummy"},
                new Object[]{"value"});

        assertThatThrownBy(() -> PlatformJmxResponse.decode(wrong))
                .as("wrong CompositeType response MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CompositeType");
    }

    @Test
    void decode_response_null_outcome_throws() throws OpenDataException {
        CompositeData response = buildResponseWith(Map.of(), new String[]{"outcome"});
        assertThatThrownBy(() -> PlatformJmxResponse.decode(response))
                .as("null outcome field MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    void decode_response_blank_outcome_throws() throws OpenDataException {
        CompositeData response = buildResponseWith(Map.of("outcome", "   "), new String[0]);
        assertThatThrownBy(() -> PlatformJmxResponse.decode(response))
                .as("blank outcome field MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    void builder_non_numeric_route_ratio_throws() {
        MockEnvironment env = fullPolicyEnvironment();
        putAuthoritativeRouteRatio(env, "/api", "not-a-number");

        ConfigServerSettingsManifestBuilder builder = new ConfigServerSettingsManifestBuilder(env);

        assertThatThrownBy(builder::build)
                .as("non-numeric route-ratio value MUST throw TracingSettingsManifestException")
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("'platform.tracing.policy.sampling.route-ratios./api'");
    }

    @Test
    void builder_route_ratio_above_one_throws() {
        MockEnvironment env = fullPolicyEnvironment();
        putAuthoritativeRouteRatio(env, "/api", "1.5");

        ConfigServerSettingsManifestBuilder builder = new ConfigServerSettingsManifestBuilder(env);

        assertThatThrownBy(builder::build)
                .as("route-ratio > 1.0 MUST throw IllegalArgumentException (value invariant)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("/api");
    }

    @Test
    void builder_route_ratio_below_zero_throws() {
        MockEnvironment env = fullPolicyEnvironment();
        putAuthoritativeRouteRatio(env, "/api", "-0.1");

        ConfigServerSettingsManifestBuilder builder = new ConfigServerSettingsManifestBuilder(env);

        assertThatThrownBy(builder::build)
                .as("route-ratio < 0.0 MUST throw IllegalArgumentException (value invariant)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("/api");
    }

    private static CompositeType wrongCompositeType() {
        try {
            return new CompositeType(
                    "WrongType/v999", "WrongType/v999",
                    new String[]{"dummy"}, new String[]{"dummy"},
                    new OpenType<?>[]{SimpleType.STRING}
            );
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static CompositeType responseType() {
        try {
            String[] keys = RESPONSE_KEYS;
            return new CompositeType(
                    "space.br1440.platform.tracing.control.ApplySnapshotResponse.v1",
                    "space.br1440.platform.tracing.control.ApplySnapshotResponse.v1",
                    keys, keys,
                    new OpenType<?>[]{
                            SimpleType.LONG, SimpleType.STRING, SimpleType.STRING, SimpleType.STRING,
                            SimpleType.STRING, arrayOfStrings()
                    });
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static ArrayType<String> arrayOfStrings() {
        try {
            return new ArrayType<>(1, SimpleType.STRING);
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static CompositeData buildResponseWith(Map<String, Object> nonNullOverrides,
                                                   String[] nullFields) throws OpenDataException {
        java.util.Set<String> nullSet = java.util.Set.of(nullFields);
        Object[] values = new Object[RESPONSE_KEYS.length];
        for (int i = 0; i < RESPONSE_KEYS.length; i++) {
            String key = RESPONSE_KEYS[i];
            if (nullSet.contains(key)) {
                values[i] = null;
            } else {
                values[i] = nonNullOverrides.getOrDefault(key, defaultForResponseField(key));
            }
        }
        return new CompositeDataSupport(RESPONSE_TYPE, RESPONSE_KEYS, values);
    }

    private static Object defaultForResponseField(String key) {
        return switch (key) {
            case "acceptedSequence" -> 1L;
            case "attemptId" -> "attempt-1";
            case "messageCode" -> "NONE";
            case "outcome" -> "APPLIED";
            case "agentInstanceId" -> "310ad563-bbe5-49cb-a72e-f3bb82bc32f2";
            case "violationCodes" -> new String[0];
            default -> throw new IllegalArgumentException("Unknown key: " + key);
        };
    }

    private static MockEnvironment fullPolicyEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(
                "configserver:test",
                Map.of(
                        "platform.tracing.policy.sampling.enabled", "true",
                        "platform.tracing.policy.sampling.ratio", "1.0",
                        "platform.tracing.policy.validation.level", "WARNING",
                        "platform.tracing.policy.exporter.enabled", "true",
                        "platform.tracing.policy.propagation.trace-control-headers.enabled", "true",
                        "platform.tracing.policy.diagnostics.log-level", "INFO"
                )));
        return env;
    }

    @SuppressWarnings("SameParameterValue")
    private static void putAuthoritativeRouteRatio(MockEnvironment env, String route, String value) {
        env.getPropertySources().addFirst(new MapPropertySource(
                "configserver:test-route-ratios",
                Map.of("platform.tracing.policy.sampling.route-ratios." + route, value))
        );
    }
}
