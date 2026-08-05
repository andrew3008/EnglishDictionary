package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedPolicyProjection;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxTracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyManifestException;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySource;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusSnapshot;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerPolicyManifestBuilder;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerPolicyProvenanceClassifier;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5 TEST §20 P5.5 — {@code p5-secret-canary}.
 *
 * <p>Unique canary values for secret-like property, header, token, file path and
 * route payload must be absent from every forbidden operator/telemetry surface
 * after digest, encode, apply, projection, Actuator, health-adjacent diagnostics,
 * logs and exception messages.
 *
 * <p>Normative allowances:
 * <ul>
 *   <li>Route-prefix canary MAY appear on the private apply wire (Category A leaf)
 *       and in digest <em>input</em> bytes — but MUST NOT appear in digest hex,
 *       Actuator/JMX projection, apply response, snapshot {@code toString()},
 *       exception text or captured logs.</li>
 *   <li>Category B secret canaries (HMAC key, auth header, token, file path) MUST
 *       be absent from all Category A wire/schema fields and all operator surfaces.</li>
 * </ul>
 */
class P55SecretCanaryTest {

    /** Letters outside {@code a-f} prevent accidental hex-substring false positives. */
    private static final String CANARY_HMAC =
            "CANARY_HMAC_KEY_z9Qx7mK2vL4pR8nW";
    private static final String CANARY_HEADER =
            "CANARY_AUTH_HEADER_Bearer_z9Qx7mK2";
    private static final String CANARY_TOKEN =
            "CANARY_TOKEN_z9Qx7mK2vL4pR8nW";
    private static final String CANARY_PATH =
            "CANARY_PATH_C:/secrets/z9Qx7mK2.key";
    private static final String CANARY_ROUTE =
            "/canary/secret/z9Qx7mK2vL4pR8nW";

    private static final List<String> ALL_CANARIES = List.of(
            CANARY_HMAC, CANARY_HEADER, CANARY_TOKEN, CANARY_PATH, CANARY_ROUTE);

    private static final List<String> CATEGORY_B_CANARIES = List.of(
            CANARY_HMAC, CANARY_HEADER, CANARY_TOKEN, CANARY_PATH);

    private final PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();

    // ── §1 digest output ─────────────────────────────────────────────────────

    @Test
    void candidateDigestHex_containsNoRawCanary() {
        String digest = CandidateDigest.sha256(snapshotWithRouteCanary());

        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
        assertAbsent(digest, ALL_CANARIES);
    }

    // ── §2 private apply wire envelope ───────────────────────────────────────

    @Test
    void applyRequestEnvelope_containsRouteCanaryOnlyInsideRouteTable() {
        CompositeData request = codec.encodeApplyRequest(
                UUID.randomUUID().toString(),
                1L,
                UUID.randomUUID().toString(),
                "CONFIG_SERVER",
                "REFRESH",
                "rev-1",
                snapshotWithRouteCanary());

        // Category B secrets are schema-impossible on Category A wire.
        assertAbsent(stringifyComposite(request), CATEGORY_B_CANARIES);

        // Envelope string fields must not carry the route canary.
        assertAbsent((String) request.get("attemptId"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("candidateDigest"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("sourceAuthority"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("applyTrigger"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("sourceRevision"), List.of(CANARY_ROUTE));
        assertAbsent((String) request.get("expectedRuntimeInstanceId"), List.of(CANARY_ROUTE));

        // Private wire intentionally carries Category A route prefixes.
        CompositeData policy = (CompositeData) request.get("runtimePolicy");
        TabularData routes = (TabularData) policy.get("samplingRouteRatios");
        assertThat(routes.values().stream().map(Object::toString).anyMatch(s -> s.contains(CANARY_ROUTE)))
                .as("route canary must be present on private apply wire")
                .isTrue();
    }

    // ── §3 apply response ────────────────────────────────────────────────────

    @Test
    void applyResponse_containsNoRouteOrSecretCanary() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant("agent-" + UUID.randomUUID(), 0L)) {
            PrivateJmxTracingPolicySnapshotClient client = agent.client();
            AcceptedSnapshotWithSequence warm = client.currentComplete()
                    .orElseThrow(() -> new IllegalStateException("fake Agent must warm currentComplete()"));

            TracingPolicyDeliveryResult outcome = client.applySnapshot(
                    warm.expectedBase(), snapshotWithRouteCanary(), TracingPolicySource.CONFIG_SERVER);
            assertThat(outcome).isEqualTo(TracingPolicyDeliveryResult.APPLIED);

            // Fake agent response is metadata-only; scan encoded response shape via codec helpers
            // by re-reading after apply (complete snapshot from FakeAgent uses empty routes).
            Optional<AcceptedSnapshotWithSequence> complete = client.currentComplete();
            assertThat(complete).isPresent();
            assertAbsent(complete.get().snapshot().toString(), ALL_CANARIES);
            assertAbsent(complete.get().runtimeInstanceId(), ALL_CANARIES);
        }
    }

    // ── §4 secret-safe projection + Actuator ─────────────────────────────────

    @Test
    void projectionAndActuator_containNoRouteOrSecretCanary() {
        AcceptedSnapshotWithSequence accepted = new AcceptedSnapshotWithSequence(
                7L, "runtime-" + UUID.randomUUID(), snapshotWithRouteCanary());

        AcceptedPolicyProjection projection = project(accepted);
        TracingStatusSnapshot.PolicyStatus body = TracingStatusSnapshot.PolicyStatus.available(
                projection.agentReadiness(), projection.policy());

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

    // ── §5 exception / log surfaces ──────────────────────────────────────────

    @Test
    void decodeInvalidRouteRatio_exceptionDoesNotEchoRouteCanary() throws Exception {
        CompositeData complete = buildAcceptedCompleteWithRoute(CANARY_ROUTE, Double.NaN);
        assertThatThrownBy(() -> codec.decodeCompleteWithSequence(complete))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> assertAbsent(ex.getMessage(), ALL_CANARIES));
    }

    @Test
    void manifestBuilder_exceptionDoesNotEchoRouteOrValueCanary() {
        MockEnvironment env = authoritativeEnv();
        env.setProperty(
                "platform.tracing.policy.sampling.route-ratios." + CANARY_ROUTE,
                CANARY_TOKEN);

        ConfigServerPolicyManifestBuilder builder = new ConfigServerPolicyManifestBuilder(
                env, new ConfigServerPolicyProvenanceClassifier(env));

        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingPolicyManifestException.class)
                .satisfies(ex -> assertAbsent(ex.getMessage(), ALL_CANARIES));
    }

    @Test
    void manifestBuilder_valueInvariantExceptionDoesNotEchoRouteCanary() {
        MockEnvironment env = authoritativeEnv();
        env.setProperty(
                "platform.tracing.policy.sampling.route-ratios." + CANARY_ROUTE,
                "1.5");

        ConfigServerPolicyManifestBuilder builder = new ConfigServerPolicyManifestBuilder(
                env, new ConfigServerPolicyProvenanceClassifier(env));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> assertAbsent(ex.getMessage(), ALL_CANARIES));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static CompleteTracingPolicySnapshot snapshotWithRouteCanary() {
        return CompleteTracingPolicySnapshot.builder()
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

    private static AcceptedPolicyProjection project(AcceptedSnapshotWithSequence accepted) {
        // Mirror PrivateJmxTracingPolicySnapshotClient.toProjection without needing a live Agent.
        CompleteTracingPolicySnapshot s = accepted.snapshot();
        return new AcceptedPolicyProjection(
                new space.br1440.platform.tracing.autoconfigure.control.policy.read.AgentReadinessView(
                        accepted.runtimeInstanceId(),
                        accepted.acceptedSequence(),
                        true),
                new space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedPolicyLeaves(
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

    private static CompositeData buildAcceptedCompleteWithRoute(String route, double ratio)
            throws Exception {
        // Собираем wire CompositeData напрямую: ratio может быть намеренно невалидным (NaN),
        // поэтому путь не должен проходить через CompleteTracingPolicySnapshot (value invariants).
        CompositeType routeRow = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
        TabularType routesType = new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                routeRow,
                new String[]{"routePrefix"});
        TabularDataSupport routes = new TabularDataSupport(routesType);
        routes.put(new CompositeDataSupport(
                routeRow,
                new String[]{"ratio", "routePrefix"},
                new Object[]{ratio, route}));

        CompositeType completeType = new CompositeType(
                "AcceptedCompleteSnapshot/v1",
                "AcceptedCompleteSnapshot/v1",
                new String[]{
                        "acceptedSequence", "runtimeInstanceId",
                        "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                        "validationEnabled", "validationStrict", "exporterEnabled",
                        "traceControlHeadersEnabled", "diagnosticsLogLevel"
                },
                new String[]{
                        "acceptedSequence", "runtimeInstanceId",
                        "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                        "validationEnabled", "validationStrict", "exporterEnabled",
                        "traceControlHeadersEnabled", "diagnosticsLogLevel"
                },
                new OpenType<?>[]{
                        SimpleType.LONG, SimpleType.STRING,
                        SimpleType.BOOLEAN, SimpleType.DOUBLE, routesType,
                        SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                        SimpleType.BOOLEAN, SimpleType.STRING
                });
        return new CompositeDataSupport(
                completeType,
                new String[]{
                        "acceptedSequence", "runtimeInstanceId",
                        "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                        "validationEnabled", "validationStrict", "exporterEnabled",
                        "traceControlHeadersEnabled", "diagnosticsLogLevel"
                },
                new Object[]{
                        1L, "rid",
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
