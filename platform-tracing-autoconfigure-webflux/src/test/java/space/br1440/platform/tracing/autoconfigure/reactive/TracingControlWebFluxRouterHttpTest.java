package space.br1440.platform.tracing.autoconfigure.reactive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicySnapshotApplier;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicySource;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyMutationService;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-WF.4 — WebFlux route/contract parity with {@code TracingControlMvcControllerHttpTest}.
 * Binds the production {@link TracingControlWebFluxManagementContextConfiguration} router
 * via WebTestClient; proves eight distinct absolute paths, leaf discriminators, and
 * outcome HTTP status mapping without a full management child-context boot.
 */
class TracingControlWebFluxRouterHttpTest {

    private WebTestClient client;
    private CapturingSnapshotClient capturedClient;

    @BeforeEach
    void setUp() {
        CompleteTracingPolicySnapshot initial = CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of("checkout", 0.5))
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();

        CompleteTracingPolicyReader stubReader = () ->
                Optional.of(new AcceptedSnapshotWithSequence(10L, "test-runtime-instance", initial));

        capturedClient = new CapturingSnapshotClient();
        TracingPolicyMutationService adapter =
                new TracingPolicyMutationService(stubReader, capturedClient);

        TracingControlWebFluxManagementContextConfiguration configuration =
                new TracingControlWebFluxManagementContextConfiguration();
        setManagementBasePath(configuration, "/actuator");
        RouterFunction<ServerResponse> routes = configuration.tracingControlWebFluxRoutes(adapter);
        client = WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void all_eight_routes_are_not_ambiguous() {
        record RouteCase(String method, String path, String body, String expectedLeaf) {}
        RouteCase[] cases = {
                new RouteCase("POST", "/actuator/tracingControl/sampling/enabled",
                        "{\"enabled\":true}", "sampling.enabled"),
                new RouteCase("POST", "/actuator/tracingControl/sampling/ratio",
                        "{\"ratio\":0.9}", "sampling.ratio"),
                new RouteCase("PUT", "/actuator/tracingControl/sampling/route-ratios",
                        "{\"routeRatios\":{}}", "sampling.route-ratios"),
                new RouteCase("POST", "/actuator/tracingControl/validation/enabled",
                        "{\"enabled\":true}", "validation.enabled"),
                new RouteCase("POST", "/actuator/tracingControl/validation/strict",
                        "{\"strict\":false}", "validation.strict"),
                new RouteCase("POST", "/actuator/tracingControl/exporter/enabled",
                        "{\"enabled\":true}", "exporter.enabled"),
                new RouteCase("POST", "/actuator/tracingControl/propagation/trace-control-headers-enabled",
                        "{\"enabled\":true}", "propagation.trace-control-headers.enabled"),
                new RouteCase("POST", "/actuator/tracingControl/diagnostics/log-level",
                        "{\"level\":\"WARN\"}", "diagnostics.log-level"),
        };

        Set<String> seenLeaves = new HashSet<>();
        for (RouteCase c : cases) {
            var spec = "PUT".equals(c.method())
                    ? client.put().uri(c.path())
                    : client.post().uri(c.path());
            Map<?, ?> body = spec.contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(c.body())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Map.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(body).isNotNull();
            String actualLeaf = (String) body.get("leaf");
            assertThat(actualLeaf).isEqualTo(c.expectedLeaf());
            assertThat(seenLeaves.add(actualLeaf))
                    .as("Duplicate leaf '%s' — routes are ambiguous", actualLeaf)
                    .isTrue();
        }
        assertThat(seenLeaves).hasSize(8);
    }

    @Test
    void custom_management_base_path_is_composed() {
        TracingControlWebFluxManagementContextConfiguration configuration =
                new TracingControlWebFluxManagementContextConfiguration();
        setManagementBasePath(configuration, "/management");
        TracingPolicyMutationService adapter = new TracingPolicyMutationService(
                () -> Optional.of(new AcceptedSnapshotWithSequence(
                        1L, "rt", CompleteTracingPolicySnapshot.builder()
                        .samplingEnabled(true)
                        .samplingRatio(1.0)
                        .samplingRouteRatios(Map.of())
                        .validationEnabled(true)
                        .validationStrict(false)
                        .exporterEnabled(true)
                        .traceControlHeadersEnabled(true)
                        .diagnosticsLogLevel("INFO")
                        .build())),
                new CapturingSnapshotClient());
        WebTestClient custom = WebTestClient
                .bindToRouterFunction(configuration.tracingControlWebFluxRoutes(adapter))
                .build();

        custom.post().uri("/management/tracingControl/sampling/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":false}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.leaf").isEqualTo("sampling.enabled");
    }

    @Test
    void outcome_mapping_matches_servlet_contract() {
        AtomicReference<TracingPolicyDeliveryResult> next = new AtomicReference<>();
        TracingPolicyMutationService adapter = new TracingPolicyMutationService(
                () -> Optional.of(new AcceptedSnapshotWithSequence(
                        1L, "rt", CompleteTracingPolicySnapshot.builder()
                        .samplingEnabled(true)
                        .samplingRatio(1.0)
                        .samplingRouteRatios(Map.of())
                        .validationEnabled(true)
                        .validationStrict(false)
                        .exporterEnabled(true)
                        .traceControlHeadersEnabled(true)
                        .diagnosticsLogLevel("INFO")
                        .build())),
                (seq, snapshot, source) -> next.get());

        TracingControlWebFluxManagementContextConfiguration configuration =
                new TracingControlWebFluxManagementContextConfiguration();
        setManagementBasePath(configuration, "/actuator");
        WebTestClient mapped = WebTestClient
                .bindToRouterFunction(configuration.tracingControlWebFluxRoutes(adapter))
                .build();

        next.set(TracingPolicyDeliveryResult.REJECTED);
        mapped.post().uri("/actuator/tracingControl/sampling/ratio")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ratio\":1.5}")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.outcome").isEqualTo("REJECTED");

        next.set(TracingPolicyDeliveryResult.STALE);
        mapped.post().uri("/actuator/tracingControl/sampling/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":true}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.outcome").isEqualTo("STALE");

        next.set(TracingPolicyDeliveryResult.FAILED);
        mapped.post().uri("/actuator/tracingControl/exporter/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":false}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.outcome").isEqualTo("FAILED");
    }

    @Test
    void route_ratios_are_preserved_after_exporter_mutation() {
        client.post().uri("/actuator/tracingControl/exporter/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":false}")
                .exchange()
                .expectStatus().isOk();

        CompleteTracingPolicySnapshot applied = capturedClient.lastSnapshot;
        assertThat(applied).isNotNull();
        assertThat(applied.samplingRouteRatios()).containsEntry("checkout", 0.5);
        assertThat(applied.exporterEnabled()).isFalse();
    }

    private static void setManagementBasePath(
            TracingControlWebFluxManagementContextConfiguration configuration, String path) {
        try {
            var field = TracingControlWebFluxManagementContextConfiguration.class
                    .getDeclaredField("managementBasePath");
            field.setAccessible(true);
            field.set(configuration, path);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static final class CapturingSnapshotClient implements TracingPolicySnapshotApplier {
        volatile CompleteTracingPolicySnapshot lastSnapshot;

        @Override
        public TracingPolicyDeliveryResult applySnapshot(
                long seq, CompleteTracingPolicySnapshot snapshot, TracingPolicySource source) {
            this.lastSnapshot = snapshot;
            return TracingPolicyDeliveryResult.APPLIED;
        }
    }
}
