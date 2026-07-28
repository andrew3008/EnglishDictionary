package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader;
import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySource;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves eight distinct HTTP routes are registered on the management controller.
 * Uses MockMvc standaloneSetup which is authoritative for Spring MVC route registration.
 * Each test hits a unique path and verifies the response $.leaf discriminator.
 * all_eight_routes_are_not_ambiguous reads actual JSON response, not hardcoded strings.
 * <p>
 * Note: standaloneSetup proves Spring MVC route uniqueness but does not prove
 * management-context auto-discovery. That is proven by ManagementContextConfiguration.imports
 * and should be validated with a full Boot integration test in a downstream test module.
 */
class TracingControlMvcControllerHttpTest {

    private MockMvc mockMvc;
    private CapturingSnapshotClient capturedClient;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        // Stub reader backed by Agent (Variant A)
        AcceptedTracingPolicySnapshotReader stubReader = () ->
                Optional.of(new AcceptedSnapshotWithSequence(10L, initial));

        capturedClient = new CapturingSnapshotClient();
        SpringActuatorTracingPolicyAdapter adapter =
                new SpringActuatorTracingPolicyAdapter(stubReader, capturedClient);
        TracingControlMvcController controller =
                new TracingControlMvcController(adapter, "/actuator");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void post_sampling_enabled_isDistinctRoute() throws Exception {
        mockMvc.perform(post("/actuator/tracingControl/sampling/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaf").value("sampling.enabled"));
    }

    @Test
    void post_sampling_ratio_isDistinctRoute() throws Exception {
        mockMvc.perform(post("/actuator/tracingControl/sampling/ratio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratio\": 0.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaf").value("sampling.ratio"));
    }

    @Test
    void put_sampling_routeRatios_isDistinctRoute() throws Exception {
        mockMvc.perform(put("/actuator/tracingControl/sampling/route-ratios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeRatios\": {\"api\": 0.8}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaf").value("sampling.route-ratios"));
    }

    @Test
    void post_validation_enabled_isDistinctRoute() throws Exception {
        mockMvc.perform(post("/actuator/tracingControl/validation/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaf").value("validation.enabled"));
    }

    @Test
    void post_validation_strict_isDistinctRoute() throws Exception {
        mockMvc.perform(post("/actuator/tracingControl/validation/strict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strict\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaf").value("validation.strict"));
    }

    @Test
    void post_exporter_enabled_isDistinctRoute() throws Exception {
        mockMvc.perform(post("/actuator/tracingControl/exporter/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaf").value("exporter.enabled"));
    }

    @Test
    void post_propagation_traceControlHeadersEnabled_isDistinctRoute() throws Exception {
        mockMvc.perform(post("/actuator/tracingControl/propagation/trace-control-headers-enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaf").value("propagation.trace-control-headers.enabled"));
    }

    @Test
    void post_diagnostics_logLevel_isDistinctRoute() throws Exception {
        mockMvc.perform(post("/actuator/tracingControl/diagnostics/log-level")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\": \"DEBUG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaf").value("diagnostics.log-level"));
    }

    @Test
    void all_eight_routes_are_not_ambiguous() throws Exception {
        record RouteCase(String method, String path, String body, String expectedLeaf) {}
        RouteCase[] cases = {
            new RouteCase("POST", "/actuator/tracingControl/sampling/enabled",
                    "{\"enabled\":true}",       "sampling.enabled"),
            new RouteCase("POST", "/actuator/tracingControl/sampling/ratio",
                    "{\"ratio\":0.9}",          "sampling.ratio"),
            new RouteCase("PUT",  "/actuator/tracingControl/sampling/route-ratios",
                    "{\"routeRatios\":{}}",     "sampling.route-ratios"),
            new RouteCase("POST", "/actuator/tracingControl/validation/enabled",
                    "{\"enabled\":true}",       "validation.enabled"),
            new RouteCase("POST", "/actuator/tracingControl/validation/strict",
                    "{\"strict\":false}",       "validation.strict"),
            new RouteCase("POST", "/actuator/tracingControl/exporter/enabled",
                    "{\"enabled\":true}",       "exporter.enabled"),
            new RouteCase("POST", "/actuator/tracingControl/propagation/trace-control-headers-enabled",
                    "{\"enabled\":true}",       "propagation.trace-control-headers.enabled"),
            new RouteCase("POST", "/actuator/tracingControl/diagnostics/log-level",
                    "{\"level\":\"WARN\"}",     "diagnostics.log-level"),
        };

        Set<String> seenLeaves = new HashSet<>();
        for (RouteCase c : cases) {
            var request = "PUT".equals(c.method())
                    ? put(c.path()).contentType(MediaType.APPLICATION_JSON).content(c.body())
                    : post(c.path()).contentType(MediaType.APPLICATION_JSON).content(c.body());

            MvcResult result = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn();

            // Read ACTUAL leaf from response body — not a hardcoded expected string
            @SuppressWarnings("unchecked")
            Map<String, Object> body = MAPPER.readValue(
                    result.getResponse().getContentAsString(), Map.class);
            String actualLeaf = (String) body.get("leaf");
            assertNotNull(actualLeaf, "Route " + c.path() + " returned no 'leaf' field");
            assertEquals(c.expectedLeaf(), actualLeaf,
                    "Route " + c.path() + " returned wrong leaf");
            assertTrue(seenLeaves.add(actualLeaf),
                    "Duplicate leaf '" + actualLeaf + "' — routes are ambiguous");
        }
        assertEquals(8, seenLeaves.size(), "Expected 8 unique leaves");
    }

    @Test
    void route_ratios_are_preserved_after_exporter_mutation() throws Exception {
        // Proves Actuator adapter does not erase route-ratios when mutating another leaf
        mockMvc.perform(post("/actuator/tracingControl/exporter/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk());

        CompleteTracingPolicySnapshot applied = capturedClient.lastSnapshot;
        assertNotNull(applied, "No snapshot was applied");
        assertEquals(1, applied.samplingRouteRatios().size(),
                "Route ratios must be preserved after single-leaf mutation");
        assertTrue(applied.samplingRouteRatios().containsKey("checkout"));
        assertEquals(0.5, applied.samplingRouteRatios().get("checkout"));
        assertEquals(false, applied.exporterEnabled());
    }

    static final class CapturingSnapshotClient implements TracingPolicySnapshotClient {
        volatile CompleteTracingPolicySnapshot lastSnapshot;
        @Override
        public TracingPolicyDeliveryResult applySnapshot(
                long seq, CompleteTracingPolicySnapshot snapshot, TracingPolicySource source) {
            this.lastSnapshot = snapshot;
            return TracingPolicyDeliveryResult.APPLIED;
        }
    }
}
