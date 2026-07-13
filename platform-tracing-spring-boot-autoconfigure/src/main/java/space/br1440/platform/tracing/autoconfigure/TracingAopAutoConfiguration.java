package space.br1440.platform.tracing.autoconfigure;

import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;

/**
 * Авто-конфигурация AOP-обработки аннотации {@code @Traced}.
 * <p>
 * Активна только при наличии Spring AOP в classpath и положительном значении свойства
 * {@code platform.tracing.enabled}. Использует {@link EnableAspectJAutoProxy} с {@code proxyTargetClass=true}
 * для поддержки proxy-перехвата как для интерфейсов, так и для классов.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnClass({Aspect.class})
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingAopAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingAopAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TracedAspect platformTracedAspect(TraceOperations traceOperations,
                                               TracingProperties properties,
                                               org.springframework.beans.factory.ObjectProvider<ExceptionRecorder> exceptionRecorderProvider) {
        ExceptionRecorder recorder = exceptionRecorderProvider.getIfAvailable(ExceptionRecorder::secureDefault);
        return new TracedAspect(traceOperations, properties.getAop().getMode(), recorder);
    }

    /**
     * Однократный WARN при использовании legacy-режима {@link TracingProperties.Aop.Mode#CHILD_SPAN}.
     * <p>
     * Платформенный default — {@link TracingProperties.Aop.Mode#ENRICH_CURRENT}: он избегает
     * дублирующих span'ов при совместной работе с OpenTelemetry Java Agent и расходует меньше
     * ресурсов на сбор. {@code CHILD_SPAN} оставлен для совместимости с ранними прототипами
     * стартера; явное предупреждение в логе помогает прикладным командам осознанно перейти
     * на новый default.
     */
    @Bean
    static SmartInitializingSingleton platformTracedAspectModeWarner(TracingProperties properties) {
        return () -> {
            if (properties.getAop().getMode() == TracingProperties.Aop.Mode.CHILD_SPAN) {
                log.warn("platform.tracing.aop.mode=CHILD_SPAN — рекомендуется ENRICH_CURRENT (default), "
                        + "иначе при работе вместе с OpenTelemetry Java Agent возможно дублирование span'ов "
                        + "для каждого @Traced-метода в стеке вызовов.");
            }
        };
    }
}
