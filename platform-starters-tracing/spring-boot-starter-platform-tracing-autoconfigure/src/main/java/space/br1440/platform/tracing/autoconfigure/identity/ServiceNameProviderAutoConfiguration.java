package space.br1440.platform.tracing.autoconfigure.identity;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
public class ServiceNameProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformLocalServiceNameProvider.class)
    public PlatformLocalServiceNameProvider platformLocalServiceNameProvider(Environment environment) {
        return new PlatformLocalServiceNameProvider(environment);
    }
}
