package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;

/**
 * Автоматическая регистрация {@link TracingProperties} в режиме {@link RefreshScope}.
 * <p>
 * После выполнения {@code POST /actuator/refresh} (или события {@code RefreshScopeRefreshedEvent})
 * Spring Cloud перерегистрирует bean {@link TracingProperties}, перечитывая значения из
 * {@code application.yml} / Spring Cloud Config / окружения. Это поведение покрывает требование
 * о динамической смене параметров без перезапуска JVM.
 * <p>
 * Подключение Spring Cloud Context опционально: класс активируется только при наличии
 * {@link RefreshScope}; в проектах без Spring Cloud auto-configuration не активируется и
 * стандартное singleton-поведение {@link TracingProperties} остаётся неизменным.
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnClass(RefreshScope.class)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingRefreshScopeAutoConfiguration {

    @Bean
    @RefreshScope
    @Primary
    public TracingProperties refreshableTracingProperties() {
        log.info("TracingProperties registered in RefreshScope: dynamic refresh via POST /actuator/refresh");
        return new TracingProperties();
    }

    @Bean
    public RuntimeConfigApplier tracingRuntimeConfigApplier(PlatformTracingJmxClient platformTracingJmxClient,
                                                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new RuntimeConfigApplier(platformTracingJmxClient, meterRegistryProvider.getIfAvailable());
    }

    @Bean
    public ApplicationListener<RefreshScopeRefreshedEvent> tracingRefreshListener(TracingProperties properties,
                                                                                  RuntimeConfigApplier runtimeConfigApplier) {
        return event -> {
            runtimeConfigApplier.applyAll(properties);
            log.info("Tracing runtime configuration applied to agent (one-call-per-domain) after RefreshScope");
        };
    }
}
