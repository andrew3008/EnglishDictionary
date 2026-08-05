package space.br1440.platform.tracing.autoconfigure.identity;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceNameResolver;

@AutoConfiguration
public class ServiceNameProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformLocalServiceNameProvider.class)
    public PlatformLocalServiceNameProvider platformLocalServiceNameProvider(Environment environment) {
        return new PlatformLocalServiceNameProvider(environment);
    }

    @Bean
    @ConditionalOnMissingBean(PlatformRemoteServiceNameProvider.class)
    public PlatformRemoteServiceNameProvider platformRemoteServiceNameProvider(ObjectProvider<RemoteServiceNameSource> contributors) {
        List<RemoteServiceNameSource> sources = contributors.orderedStream().toList();
        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(sources);
        return new PlatformRemoteServiceNameProvider(resolver);
    }
}
