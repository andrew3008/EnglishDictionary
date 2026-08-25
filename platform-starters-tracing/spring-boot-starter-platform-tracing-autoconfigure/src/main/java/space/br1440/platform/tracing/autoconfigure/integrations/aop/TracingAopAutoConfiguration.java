package space.br1440.platform.tracing.autoconfigure.integrations.aop;

import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnClass({Aspect.class})
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingAopAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TracedAspect platformTracedAspect(TraceOperations traceOperations,
                                             ObjectProvider<ExceptionRecorder> exceptionRecorderProvider) {
        ExceptionRecorder recorder = exceptionRecorderProvider.getIfAvailable(ExceptionRecorder::secureDefault);
        return new TracedAspect(traceOperations, recorder);
    }
}
