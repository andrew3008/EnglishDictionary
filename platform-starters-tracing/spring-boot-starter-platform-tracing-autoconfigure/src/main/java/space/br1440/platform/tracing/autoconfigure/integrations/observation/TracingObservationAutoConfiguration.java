package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingObservationAutoConfiguration {

    @Bean
    public SmartInitializingSingleton platformHttpObservationSuppressionStartupRunner(Environment environment) {
        boolean isResolved = HttpObservationSuppressionResolver.resolve(environment);
        return HttpObservationSuppressionStartupRunner.create(isResolved);
    }
}
