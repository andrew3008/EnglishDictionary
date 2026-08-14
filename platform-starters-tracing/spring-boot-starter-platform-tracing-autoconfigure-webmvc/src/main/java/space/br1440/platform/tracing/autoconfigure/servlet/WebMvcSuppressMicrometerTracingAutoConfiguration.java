package space.br1440.platform.tracing.autoconfigure.servlet;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import space.br1440.platform.tracing.autoconfigure.agent.OtelJavaAgentMarkerDetector;
import space.br1440.platform.tracing.autoconfigure.integrations.observation.SuppressMicrometerTracingCondition;

@Slf4j
@AutoConfiguration(after = ObservationAutoConfiguration.class)
@AutoConfigureAfter(ObservationAutoConfiguration.class)
@ConditionalOnClass(ServerRequestObservationContext.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(SuppressMicrometerTracingCondition.class)
public class WebMvcSuppressMicrometerTracingAutoConfiguration {

    @Bean("platformMvcHttpObservationSuppressor")
    @ConditionalOnMissingBean(name = "platformMvcHttpObservationSuppressor")
    public ObservationRegistryCustomizer<ObservationRegistry> platformMvcHttpObservationSuppressor() {
        return registry -> {
            registry.observationConfig().observationPredicate((name, context) -> {
                return !(context instanceof ServerRequestObservationContext)
                        && !(context instanceof ClientRequestObservationContext);
            });

            log.info("Suppressed Servlet HTTP server/client Micrometer Observations — spans and metrics "
                    + "for these observations are not published. HTTP telemetry should come from "
                    + "OpenTelemetry Java Agent. (agentDetected={})",
                    OtelJavaAgentMarkerDetector.isAgentPresent());
        };
    }
}
