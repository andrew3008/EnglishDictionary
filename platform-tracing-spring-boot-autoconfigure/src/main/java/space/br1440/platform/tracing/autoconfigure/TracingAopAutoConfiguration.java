package space.br1440.platform.tracing.autoconfigure;

import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.aspect.TracedAspect;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;

/**
 * Авто-конфигурация AOP-обработки аннотации {@code @Traced}.
 * <p>
 * Активна только при наличии Spring AOP в classpath и положительном значении свойства
 * {@code platform.tracing.mode=AGENT}. Использует {@link EnableAspectJAutoProxy} с {@code proxyTargetClass=true}
 * для поддержки proxy-перехвата как для интерфейсов, так и для классов.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnClass({Aspect.class})
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "mode", havingValue = "AGENT", matchIfMissing = true)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingAopAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TracedAspect platformTracedAspect(TraceOperations traceOperations,
                                               org.springframework.beans.factory.ObjectProvider<ExceptionRecorder> exceptionRecorderProvider) {
        ExceptionRecorder recorder = exceptionRecorderProvider.getIfAvailable(ExceptionRecorder::secureDefault);
        return new TracedAspect(traceOperations, recorder);
    }
}
