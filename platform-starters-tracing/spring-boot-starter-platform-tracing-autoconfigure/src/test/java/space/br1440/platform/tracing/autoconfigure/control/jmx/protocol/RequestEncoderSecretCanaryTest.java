package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerSettingsManifestBuilder;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerSettingsProvenanceClassifier;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsReadModel;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxTracingSettingsDigest;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusSnapshot;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

final class RequestEncoderSecretCanaryTest {

    private static final String CANARY_HMAC = "CANARY_HMAC_KEY_z9Qx7mK2vL4pR8nW";
    private static final String CANARY_HEADER = "CANARY_AUTH_HEADER_Bearer_z9Qx7mK2";
    private static final String CANARY_TOKEN = "CANARY_TOKEN_z9Qx7mK2vL4pR8nW";
    private static final String CANARY_PATH = "CANARY_PATH_C:/secrets/z9Qx7mK2.key";
    private static final String CANARY_ROUTE = "/canary/secret/z9Qx7mK2vL4pR8nW";

    private static final List<String> ALL_CANARIES = List.of(
            CANARY_HMAC, CANARY_HEADER, CANARY_TOKEN, CANARY_PATH, CANARY_ROUTE
        );

    private static final List<String> CATEGORY_B_CANARIES = List.of(
            CANARY_HMAC, CANARY_HEADER, CANARY_TOKEN, CANARY_PATH
        );

    @Test
    void candidateDigestHex_containsNoRawCanary() {
        CompleteTracingSettings snapshot = snapshotWithRouteCanary();
        String digest = JmxTracingSettingsDigest.sha256(
                snapshot.samplingEnabled(),
                snapshot.samplingRatio(),
                snapshot.samplingRouteRatios(),
                snapshot.validationEnabled(),
                snapshot.validationStrict(),
                snapshot.exporterEnabled(),
                snapshot.traceControlHeadersEnabled(),
                snapshot.diagnosticsLogLevel()
        );

        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
        assertAbsent(digest, ALL_CANARIES);
    }

    @Test
    void applyRequestEnvelope_containsRouteCanaryOnlyInsideRouteTable() {
        CompositeData request = PlatformJmxRequestEncoder.encodeApplyRequest(
                ApplySnapshotRequestParameters.builder()
                        .expectedBase(new ExpectedSettingsRevision(UUID.randomUUID().toString(), 1L))
                        .attemptId(UUID.randomUUID().toString())
                        .sourceAuthority("CONFIG_SERVER")
                        .applyTrigger("REFRESH")
                        .sourceRevision("rev-1")
                        .policy(snapshotWithRouteCanary())
                        .build()
        );

        assertAbsent(stringifyComposite(request), CATEGORY_B_CANARIES);
        assertAbsent((String) request.get("attemptId"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("candidateDigest"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("sourceAuthority"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("applyTrigger"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("sourceRevision"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("expectedRuntimeInstanceId"), List.of(CANARY_ROUTE));

        CompositeData policy = (CompositeData) request.get("runtimePolicy");
        TabularData routes = (TabularData) policy.get("samplingRouteRatios");
        assertThat(routes.values().stream().map(Object::toString).anyMatch(s -> s.contains(CANARY_ROUTE)))
                .as("route canary must be present on private apply wire")
                .isTrue();
    }

    @Test
    void applyResponse_containsNoRouteOrSecretCanary() throws Exception {
        try (FakeAgentPlatformJmxPlane agent =
                     FakeAgentPlatformJmxPlane.compliant("agent-" + UUID.randomUUID(), 0L)) {
            PlatformJmxTracingSettingsClient client = agent.client();
            AcceptedSettingsWithSequence warm = client.currentComplete()
                    .orElseThrow(() -> new IllegalStateException("fake Agent must warm currentComplete()"));

            TracingSettingsDeliveryResult outcome = client.applySnapshot(
                    warm.expectedBase(), snapshotWithRouteCanary(), TracingSettingsDeliverySource.CONFIG_SERVER);
            assertThat(outcome).isEqualTo(TracingSettingsDeliveryResult.APPLIED);

            Optional<AcceptedSettingsWithSequence> complete = client.currentComplete();
            assertThat(complete).isPresent();
            assertAbsent(complete.get().snapshot().toString(), ALL_CANARIES);
            assertAbsent(complete.get().runtimeInstanceId(), ALL_CANARIES);
        }
    }

    @Test
    void projectionAndActuator_containNoRouteOrSecretCanary() {
        AcceptedSettingsWithSequence accepted = new AcceptedSettingsWithSequence(
                7L, "runtime-" + UUID.randomUUID(), snapshotWithRouteCanary());

        AcceptedSettingsReadModel projection = project(accepted);
        TracingStatusSnapshot.SettingsStatus body = TracingStatusSnapshot.SettingsStatus.available(
                projection.acceptedRevision(), projection.summary());

        assertAbsent(stringifyObject(body), ALL_CANARIES);
        assertThat(stringifyObject(body)).contains("samplingRouteRatiosCount");
        assertThat(stringifyObject(body)).contains("1"); // count only
    }

    @Test
    void snapshotToString_containsRouteCountNotRouteCanary() {
        String text = snapshotWithRouteCanary().toString();
        assertThat(text).contains("sampling.route-ratios.size=1");
        assertAbsent(text, ALL_CANARIES);
    }

    @Test
    void decodeInvalidRouteRatio_exceptionDoesNotEchoRouteCanary() throws Exception {
        CompositeData complete = buildAcceptedCompleteWithRoute(CANARY_ROUTE, Double.NaN);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(complete))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> assertAbsent(ex.getMessage(), ALL_CANARIES));
    }

    @Test
    void manifestBuilder_exceptionDoesNotEchoRouteOrValueCanary() {
        MockEnvironment env = authoritativeEnv();
        putAuthoritativeRouteRatio(env, CANARY_ROUTE, CANARY_TOKEN);

        ConfigServerSettingsManifestBuilder builder = new ConfigServerSettingsManifestBuilder(
                env, new ConfigServerSettingsProvenanceClassifier(env));

        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class)
                .satisfies(ex -> assertAbsent(ex.getMessage(), ALL_CANARIES));
    }

    @Test
    void manifestBuilder_valueInvariantExceptionDoesNotEchoRouteCanary() {
        MockEnvironment env = authoritativeEnv();
        putAuthoritativeRouteRatio(env, CANARY_ROUTE, "1.5");

        ConfigServerSettingsManifestBuilder builder = new ConfigServerSettingsManifestBuilder(
                env, new ConfigServerSettingsProvenanceClassifier(env));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> assertAbsent(ex.getMessage(), ALL_CANARIES));
    }

    private static CompleteTracingSettings snapshotWithRouteCanary() {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5d)
                .samplingRouteRatios(Map.of(CANARY_ROUTE, 0.25d))
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }

    private static AcceptedSettingsReadModel project(AcceptedSettingsWithSequence accepted) {
        CompleteTracingSettings s = accepted.snapshot();
        return new AcceptedSettingsReadModel(
                accepted.expectedBase(),
                new space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedPolicySummary(
                        s.samplingEnabled(),
                        s.samplingRatio(),
                        s.samplingRouteRatios().size(),
                        s.validationEnabled(),
                        s.validationStrict(),
                        s.exporterEnabled(),
                        s.traceControlHeadersEnabled(),
                        s.diagnosticsLogLevel()));
    }

    private static MockEnvironment authoritativeEnv() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(
                "configserver:canary",
                Map.of(
                        "platform.tracing.policy.sampling.enabled", "true",
                        "platform.tracing.policy.sampling.ratio", "1.0",
                        "platform.tracing.policy.validation.enabled", "true",
                        "platform.tracing.policy.validation.strict", "false",
                        "platform.tracing.policy.exporter.enabled", "true",
                        "platform.tracing.policy.propagation.trace-control-headers.enabled", "true",
                        "platform.tracing.policy.diagnostics.log-level", "INFO"
                )));
        return env;
    }

    private static void putAuthoritativeRouteRatio(MockEnvironment env, String route, String value) {
        env.getPropertySources().addFirst(new MapPropertySource(
                "configserver:canary-route",
                Map.of("platform.tracing.policy.sampling.route-ratios." + route, value)));
    }

    @SuppressWarnings("SameParameterValue")
    private static CompositeData buildAcceptedCompleteWithRoute(String route, double ratio) throws Exception {
        TabularDataSupport routes = new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS);
        routes.put(new CompositeDataSupport(
                JmxOpenTypes.ROUTE_RATIO,
                new String[]{"ratio", "routePrefix"},
                new Object[]{ratio, route}));

        return new CompositeDataSupport(
                JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT,
                new String[]{
                        "acceptedSequence", "runtimeInstanceId",
                        "acceptedPolicyDigest", "lastApplyOutcome", "lastAttemptId", "failureCode",
                        "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                        "validationEnabled", "validationStrict", "exporterEnabled",
                        "traceControlHeadersEnabled", "diagnosticsLogLevel"
                },

                new Object[]{
                        1L, "rid",
                        "accepted-policy-digest", "APPLIED", "attempt-id", "",
                        true, 1.0d, routes,
                        true, false, true,
                        true, "INFO"
                });
    }

    private static void assertAbsent(String haystack, List<String> canaries) {
        assertThat(haystack).isNotNull();
        for (String canary : canaries) {
            assertThat(haystack)
                    .as("surface must not contain canary %s", canary)
                    .doesNotContain(canary);
        }
    }

    private static String stringifyComposite(CompositeData data) {
        return data == null ? "null" : data.toString();
    }

    private static String stringifyObject(Object value) {
        return String.valueOf(value);
    }
}
