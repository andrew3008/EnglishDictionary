package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;

class ConfigServerSettingsManifestBuilderRouteRatiosTest {

    private static final String ORDERS = "platform.tracing.policy.sampling.route-ratios./work/orders";
    private static final String HEALTH = "platform.tracing.policy.sampling.route-ratios./work/health";

    @Test
    void localOverrideOfRouteRatioIsRejected() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of());
        environment.getPropertySources().addFirst(new MapPropertySource(
                "systemProperties",
                Map.of(ORDERS, "1.0")));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when / then
        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("owned by POLICY")
                .hasMessageContaining("source='systemProperties'")
                .hasMessageNotContaining("1.0");
    }

    @Test
    void unknownSourceRouteRatioIsRejected() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of());
        environment.getPropertySources().addLast(new MapPropertySource(
                "randomCustomSource",
                Map.of(ORDERS, "0.5")));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when / then
        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("owned by POLICY")
                .hasMessageContaining("source='randomCustomSource'")
                .hasMessageNotContaining("0.5");
    }

    @Test
    void localOverrideShadowingAnAuthoritativeRouteRatioIsRejected() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of(ORDERS, "0.2"));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "systemProperties",
                Map.of(ORDERS, "1.0")));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when / then
        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("owned by POLICY")
                .hasMessageContaining("source='systemProperties'")
                .hasMessageNotContaining("1.0");
    }

    @Test
    void bracketedRouteRatioKeyIsBound() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of(
                "platform.tracing.policy.sampling.route-ratios[/work/orders]", "0.25"));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when
        CompleteTracingSettings settings = builder.build();

        // then
        assertThat(settings.samplingRouteRatios())
                .as("the bracket form is legal relaxed binding and must not be silently dropped")
                .containsExactly(Map.entry("/work/orders", 0.25d));
    }

    @Test
    void bothSpellingsOfOneRouteWithTheSameValueBindOnce() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of(
                ORDERS, "0.25",
                "platform.tracing.policy.sampling.route-ratios[/work/orders]", "0.25"));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when
        CompleteTracingSettings settings = builder.build();

        // then
        assertThat(settings.samplingRouteRatios()).containsExactly(Map.entry("/work/orders", 0.25d));
    }

    @Test
    void bothSpellingsOfOneRouteWithDifferentValuesAreRejected() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of(
                ORDERS, "0.25",
                "platform.tracing.policy.sampling.route-ratios[/work/orders]", "0.75"));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when / then
        assertThatThrownBy(builder::build)
                .as("Environment precedence cannot resolve two different names, so the manifest fails closed")
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("Conflicting values for route '/work/orders'");
    }

    @Test
    void malformedBracketedRouteRatioKeyIsRejected() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of(
                "platform.tracing.policy.sampling.route-ratios[/work/orders", "0.25"));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when / then
        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining(
                        "Malformed bracketed route ratio key 'platform.tracing.policy.sampling.route-ratios[/work/orders'");
    }

    @Test
    void outOfRangeRouteRatioDoesNotEchoTheRoute() {
        // given
        String route = "/canary/secret/z9Qx7mK2vL4pR8nW";
        MockEnvironment environment = authoritativeEnvironment(Map.of(
                "platform.tracing.policy.sampling.route-ratios." + route, "1.5"));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when / then
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(route);
    }

    @Test
    void authoritativeRouteRatiosAreAccepted() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of(
                ORDERS, "1.0",
                HEALTH, "0.0"));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when
        CompleteTracingSettings settings = builder.build();

        // then
        assertThat(settings.samplingRouteRatios())
                .containsEntry("/work/orders", 1.0d)
                .containsEntry("/work/health", 0.0d)
                .hasSize(2);
    }

    @Test
    void sameRouteKeyInTwoAuthoritativeSourcesResolvesByPrecedence() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of(ORDERS, "0.2"));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "configserver:http://config-server:8888/application-prod.yml",
                Map.of(ORDERS, "0.8")));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when
        CompleteTracingSettings settings = builder.build();

        // then
        assertThat(settings.samplingRouteRatios()).containsExactly(Map.entry("/work/orders", 0.8d));
    }

    @Test
    void unknownPolicyKeyIsRejected() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of(
                "platform.tracing.policy.sampling.ration", "0.2"));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when
        // then
        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("Unknown policy key")
                .hasMessageContaining("platform.tracing.policy.sampling.ration")
                .hasMessageNotContaining("0.2");
    }

    @Test
    void legacyBootstrapPropertiesSourceIsRejected() {
        // given
        MockEnvironment environment = authoritativeEnvironment(Map.of(ORDERS, "0.2"));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "bootstrapProperties",
                Map.of(ORDERS, "0.4")));
        ConfigServerSettingsManifestBuilder builder = builder(environment);

        // when
        // then
        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("owned by POLICY")
                .hasMessageContaining("source='bootstrapProperties'");
    }

    private static ConfigServerSettingsManifestBuilder builder(MockEnvironment environment) {
        return new ConfigServerSettingsManifestBuilder(environment);
    }

    private static MockEnvironment authoritativeEnvironment(Map<String, String> extra) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource(
                        "configserver:http://config-server:8888",
                        policyValues(extra)
                )
        );
        return environment;
    }

    private static Map<String, Object> policyValues(Map<String, String> extra) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("platform.tracing.policy.sampling.enabled", "true");
        values.put("platform.tracing.policy.sampling.ratio", "1.0");
        values.put("platform.tracing.policy.validation.level", "WARNING");
        values.put("platform.tracing.policy.exporter.enabled", "true");
        values.put("platform.tracing.policy.propagation.trace-control-headers.enabled", "true");
        values.put("platform.tracing.policy.diagnostics.log-level", "INFO");
        values.putAll(extra);
        return values;
    }
}
