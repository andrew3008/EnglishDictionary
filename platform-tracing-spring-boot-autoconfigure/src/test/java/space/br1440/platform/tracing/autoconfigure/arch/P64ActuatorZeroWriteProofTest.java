package space.br1440.platform.tracing.autoconfigure.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import space.br1440.platform.tracing.autoconfigure.TracingActuatorAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.actuator.TracingActuatorEndpoint;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationAuthorityAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.configuration.actuator.SpringActuatorTracingPolicyAdapter;
import space.br1440.platform.tracing.autoconfigure.configuration.actuator.TracingControlMvcController;
import space.br1440.platform.tracing.autoconfigure.diagnostics.SpanFactoryDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * P6.4 / P6.10 residual {@code p6-production-actuator-zero-write-proof}:
 * Spring descriptor, bean, and in-process HTTP absence of the deleted legacy
 * {@code @WriteOperation} on {@code /actuator/tracing}.
 *
 * <p>Approved DEV_ACTUATOR typed leaf commands under {@code ACTUATOR+LOCAL}
 * ({@code /management/tracingControl/...}) are intentionally preserved and are
 * not deleted here — this proof only shows the legacy generic write is gone.
 */
class P64ActuatorZeroWriteProofTest {

    /**
     * Full {@code @EnableAutoConfiguration} pulls TracingCore / OTel ownership that is
     * unrelated to Actuator write absence. HTTP proofs register a real
     * {@link TracingActuatorEndpoint} bean and exclude the heavy tracing stack
     * (same approach as {@code TracingControlManagementChildContextIntegrationTest}).
     */
    private static final String[] EXCLUDED_UNRELATED_AUTOCONFIGURATIONS = {
            "space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.SemanticLayerAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingAopAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingObservationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingActuatorAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.RequestContextSupplierAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.ServiceNameProviderAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.async.TracingAsyncContextAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaOutboundAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
    };

    private final ApplicationContextRunner readOnlyEndpointRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingCoreAutoConfiguration.class,
                    TracingActuatorAutoConfiguration.class))
            .withInitializer(ControlledAgentTestRuntime::initialize)
            .withPropertyValues(
                    "platform.tracing.configuration.authority=HELM",
                    "platform.environment=PRODUCTION",
                    "management.endpoints.web.exposure.include=tracing",
                    "management.endpoint.tracing.enabled=true");

    @Test
    @DisplayName("Minimal HELM/PRODUCTION context registers diagnostics endpoint with zero @WriteOperation")
    void helmProductionContext_registersReadOnlyTracingEndpoint() {
        readOnlyEndpointRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TracingActuatorEndpoint.class);
            assertThat(context).doesNotHaveBean(SpringActuatorTracingPolicyAdapter.class);
            assertThat(context).doesNotHaveBean(TracingControlMvcController.class);

            TracingActuatorEndpoint endpoint = context.getBean(TracingActuatorEndpoint.class);
            long writeOps = Arrays.stream(endpoint.getClass().getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(WriteOperation.class))
                    .count();
            assertThat(writeOps).isZero();
            assertThat(Arrays.stream(endpoint.getClass().getDeclaredMethods()).map(Method::getName))
                    .doesNotContain("updateTracing", "assertMutationAllowed");
        });
    }

    @ParameterizedTest(name = "no write adapter: authority={0} posture={1}")
    @CsvSource({
            "HELM,LOCAL",
            "HELM,SHARED",
            "HELM,PRODUCTION",
            "CONFIG_SERVER,LOCAL",
            "CONFIG_SERVER,SHARED",
            "CONFIG_SERVER,PRODUCTION"
    })
    @DisplayName("Production/shared authority postures expose no Actuator write adapter bean")
    void productionSharedPostures_noWriteAdapterBean(String authority, String posture) throws Exception {
        if ("CONFIG_SERVER".equals(authority)) {
            try (FakeAgentPrivateControlPlane ignored =
                         FakeAgentPrivateControlPlane.compliant(UUID.randomUUID().toString(), 0L)) {
                assertNoWriteAdapter(authority, posture);
            }
        } else {
            assertNoWriteAdapter(authority, posture);
        }
    }

    @Test
    @DisplayName("HELM+DISABLED exposes no Actuator write adapter or control controller")
    void helmDisabled_noWriteSurfaceBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingConfigurationAuthorityAutoConfiguration.class,
                        space.br1440.platform.tracing.autoconfigure.configuration.actuator
                                .TracingActuatorAutoConfiguration.class))
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=PRODUCTION",
                        "platform.tracing.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringActuatorTracingPolicyAdapter.class);
                    assertThat(context).doesNotHaveBean(TracingControlMvcController.class);
                });
    }

    @Test
    @DisplayName("HELM/PRODUCTION: GET /actuator/tracing works; legacy POST write routes are absent (404/405)")
    void helmProduction_legacyWriteHttpRoutesAbsent() {
        try (ConfigurableApplicationContext context = boot(
                ReadOnlyTracingEndpointApp.class,
                "platform.tracing.configuration.authority=HELM",
                "platform.environment=PRODUCTION",
                "management.endpoints.web.exposure.include=tracing",
                "management.endpoint.tracing.enabled=true",
                "server.port=0")) {

            assertThat(context.getBean(TracingActuatorEndpoint.class)).isNotNull();
            assertWebEndpointHasNoWriteOperations(context);

            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            RestTemplate rest = new RestTemplateBuilder().build();
            String base = "http://localhost:" + port + "/actuator/tracing";

            ResponseEntity<Map> read = rest.getForEntity(base, Map.class);
            assertThat(read.getStatusCode().is2xxSuccessful())
                    .as("read-only GET /actuator/tracing must remain available")
                    .isTrue();
            assertThat(read.getBody()).doesNotContainKey("actuator");

            assertLegacyWriteAbsent(rest, base + "/samplingRatio/0.5");
            assertLegacyWriteAbsent(rest, base + "/exportEnabled/false");
            assertLegacyWriteAbsent(rest, base);
        }
    }

    @Test
    @DisplayName("ACTUATOR+LOCAL: legacy generic write is gone; typed DEV_ACTUATOR leaf route remains")
    void actuatorLocal_legacyWriteGone_typedLeafRemains() throws Exception {
        try (FakeAgentPrivateControlPlane ignored =
                     FakeAgentPrivateControlPlane.compliant("p64-zero-write-runtime", 3L)) {
            try (ConfigurableApplicationContext context = boot(
                    ReadOnlyTracingEndpointApp.class,
                    "platform.tracing.configuration.authority=ACTUATOR",
                    "platform.environment=LOCAL",
                    "management.server.port=0",
                    "management.endpoints.web.base-path=/management",
                    "management.endpoints.web.exposure.include=tracing",
                    "management.endpoint.tracing.enabled=true",
                    "server.port=0")) {

                int appPort = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
                int managementPort = Integer.parseInt(
                        context.getEnvironment().getProperty("local.management.port"));
                RestTemplate rest = new RestTemplateBuilder().build();

                assertLegacyWriteAbsent(rest,
                        "http://localhost:" + appPort + "/actuator/tracing/samplingRatio/0.5");
                assertLegacyWriteAbsent(rest,
                        "http://localhost:" + managementPort + "/management/tracing/samplingRatio/0.5");

                ResponseEntity<Map> typedLeaf = rest.postForEntity(
                        "http://localhost:" + managementPort
                                + "/management/tracingControl/sampling/enabled",
                        Map.of("enabled", true),
                        Map.class);
                assertThat(typedLeaf.getStatusCode().is2xxSuccessful())
                        .as("approved DEV_ACTUATOR typed leaf must remain on ACTUATOR+LOCAL")
                        .isTrue();
                assertThat(typedLeaf.getBody()).containsEntry("leaf", "sampling.enabled");
            }
        }
    }

    private static void assertNoWriteAdapter(String authority, String posture) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingConfigurationAuthorityAutoConfiguration.class,
                        space.br1440.platform.tracing.autoconfigure.configuration.actuator
                                .TracingActuatorAutoConfiguration.class))
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=" + posture);

        if ("CONFIG_SERVER".equals(authority)) {
            runner = new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            TracingConfigurationAuthorityAutoConfiguration.class,
                            space.br1440.platform.tracing.autoconfigure.configuration.configserver
                                    .ConfigServerTracingPolicyAutoConfiguration.class,
                            space.br1440.platform.tracing.autoconfigure.configuration.actuator
                                    .TracingActuatorAutoConfiguration.class))
                    .withInitializer(context -> {
                        org.springframework.core.env.MapPropertySource authoritative =
                                new org.springframework.core.env.MapPropertySource(
                                        "configserver:platform-tracing-policy",
                                        Map.of(
                                                "platform.tracing.policy.sampling.enabled", "true",
                                                "platform.tracing.policy.sampling.ratio", "1.0",
                                                "platform.tracing.policy.validation.enabled", "true",
                                                "platform.tracing.policy.validation.strict", "false",
                                                "platform.tracing.policy.exporter.enabled", "true",
                                                "platform.tracing.policy.propagation.trace-control-headers.enabled",
                                                "true",
                                                "platform.tracing.policy.diagnostics.log-level", "INFO"));
                        context.getEnvironment().getPropertySources().addFirst(authoritative);
                    })
                    .withPropertyValues(
                            "platform.tracing.configuration.authority=" + authority,
                            "platform.environment=" + posture);
        }

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SpringActuatorTracingPolicyAdapter.class);
            assertThat(context).doesNotHaveBean(TracingControlMvcController.class);
        });
    }

    private static void assertWebEndpointHasNoWriteOperations(ConfigurableApplicationContext context) {
        PathMappedEndpoints mapped = context.getBean(PathMappedEndpoints.class);
        PathMappedEndpoint mappedEndpoint = mapped.getEndpoint(EndpointId.of("tracing"));
        assertThat(mappedEndpoint)
                .as("web endpoint 'tracing' must be registered for descriptor proof")
                .isInstanceOf(ExposableWebEndpoint.class);
        ExposableWebEndpoint endpoint = (ExposableWebEndpoint) mappedEndpoint;
        Collection<WebOperation> operations = endpoint.getOperations();
        assertThat(operations).isNotEmpty();
        assertThat(operations)
                .extracting(WebOperation::getType)
                .doesNotContain(OperationType.WRITE);
        assertThat(operations)
                .extracting(WebOperation::getType)
                .containsOnly(OperationType.READ);
    }

    private static void assertLegacyWriteAbsent(RestTemplate rest, String url) {
        assertThatThrownBy(() -> rest.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), jsonHeaders()),
                String.class))
                .as("legacy write POST %s must be physically absent (404/405), not a business reject", url)
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(thrown -> {
                    HttpClientErrorException error = (HttpClientErrorException) thrown;
                    assertThat(error.getStatusCode().value()).isIn(404, 405);
                    assertThat(error.getResponseBodyAsString())
                            .doesNotContain("mutation-enabled", "MUTATION_DISABLED", "assertMutationAllowed");
                });

        assertThatThrownBy(() -> rest.exchange(
                url,
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(), jsonHeaders()),
                String.class))
                .as("legacy write PUT %s must be physically absent (404/405)", url)
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(thrown -> {
                    HttpClientErrorException error = (HttpClientErrorException) thrown;
                    assertThat(error.getStatusCode().value()).isIn(404, 405);
                });
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static ConfigurableApplicationContext boot(Class<?> appClass, String... properties) {
        return new SpringApplicationBuilder(appClass)
                .web(WebApplicationType.SERVLET)
                .properties(properties)
                .properties("spring.autoconfigure.exclude="
                        + String.join(",", EXCLUDED_UNRELATED_AUTOCONFIGURATIONS))
                .run();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ReadOnlyTracingEndpointApp {

        @Bean
        TracingActuatorEndpoint tracingActuatorEndpoint() {
            PlatformTracingJmxClient jmxClient = Mockito.mock(PlatformTracingJmxClient.class);
            when(jmxClient.isAvailable()).thenReturn(false);
            when(jmxClient.getCurrentRatio()).thenReturn(Optional.empty());
            return new TracingActuatorEndpoint(
                    NoopTraceOperations.INSTANCE,
                    new TracingProperties(),
                    jmxClient,
                    new SpanFactoryDiagnostics(NoOpTracingRuntime.noop()),
                    null);
        }
    }
}
