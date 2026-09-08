package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;

import space.br1440.platform.tracing.autoconfigure.agent.OtelJavaAgentMarkerDetector;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.autoconfigure.integrations.observation.HttpObservationSuppressionCondition;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AutoConfiguration(after = ObservationAutoConfiguration.class)
@AutoConfigureAfter(ObservationAutoConfiguration.class)
@ConditionalOnClass(ServerRequestObservationContext.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
@Conditional(HttpObservationSuppressionCondition.class)
public class WebFluxHttpObservationSuppressionAutoConfiguration {

    private static final String HTTP_OBSERVATION_SUPPRESSION_MESSAGE = """
        Suppressed Reactive HTTP server/client Micrometer Observations — \
        http.server.requests and http.client.requests are not published. \
        HTTP spans come from the OpenTelemetry Java Agent when attached. (agentDetected={})""";

    @Bean("platformWebFluxHttpObservationSuppressor")
    @ConditionalOnMissingBean(name = "platformWebFluxHttpObservationSuppressor")
    public ObservationRegistryCustomizer<ObservationRegistry> platformWebFluxHttpObservationSuppressor() {
        return registry -> {
            registry.observationConfig()
                    .observationPredicate(WebFluxHttpObservationSuppressionAutoConfiguration::isHttpObservationAllowed);

            log.info(HTTP_OBSERVATION_SUPPRESSION_MESSAGE, OtelJavaAgentMarkerDetector.isAgentPresent());
        };
    }

    private static boolean isHttpObservationAllowed(String name, Observation.Context context) {
        return !(context instanceof ServerRequestObservationContext
                || context instanceof ClientRequestObservationContext);
    }
}
