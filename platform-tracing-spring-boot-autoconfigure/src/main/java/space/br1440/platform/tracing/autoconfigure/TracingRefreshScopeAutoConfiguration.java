package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
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
 * Автоматическая регистрация {@link TracingProperties} в режиме {@link RefreshScope}, если на
 * classpath присутствует {@code spring-cloud-context}.
 * <p>
 * После выполнения {@code POST /actuator/refresh} (или события {@code RefreshScopeRefreshedEvent})
 * Spring Cloud перерегистрирует bean {@link TracingProperties}, перечитывая значения из
 * {@code application.yml} / Spring Cloud Config / окружения. Это поведение покрывает требование
 * §117 платформенного стандарта о динамической смене параметров без перезапуска JVM.
 * <p>
 * Подключение Spring Cloud Context опционально: класс активируется только при наличии
 * {@link RefreshScope}; в проектах без Spring Cloud auto-configuration не активируется и
 * стандартное singleton-поведение {@link TracingProperties} остаётся неизменным.
 * <p>
 * Bean помечен {@link Primary}, чтобы перекрыть стандартный singleton {@link TracingProperties},
 * регистрируемый {@link EnableConfigurationProperties} в {@link TracingCoreAutoConfiguration}.
 * Замена прозрачна для остальных bean'ов: контракт {@link TracingProperties} тот же.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnClass(RefreshScope.class)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingRefreshScopeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingRefreshScopeAutoConfiguration.class);

    @Bean
    @RefreshScope
    @Primary
    public TracingProperties refreshableTracingProperties() {
        log.info("TracingProperties зарегистрирован в RefreshScope: динамическое обновление через POST /actuator/refresh");
        return new TracingProperties();
    }

    /**
     * Тонкий аппликатор runtime-конфигурации (Фаза 14): one-call-per-domain push изменяемых
     * параметров в агент через JMX. Без собственного состояния — читает свежие значения из
     * refresh-scoped {@link TracingProperties}.
     */
    @Bean
    public RuntimeConfigApplier tracingRuntimeConfigApplier(PlatformTracingJmxClient platformTracingJmxClient,
                                                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new RuntimeConfigApplier(platformTracingJmxClient, meterRegistryProvider.getIfAvailable());
    }

    @Bean
    public ApplicationListener<RefreshScopeRefreshedEvent> tracingRefreshListener(TracingProperties properties,
                                                                                  RuntimeConfigApplier runtimeConfigApplier) {
        // properties — refresh-scoped proxy: на момент события его цель уже пересоздана из Environment,
        // поэтому applier читает актуальные значения (no stale bean).
        return event -> {
            runtimeConfigApplier.applyAll(properties);
            log.info("Runtime-конфигурация трассировки применена в агент (one-call-per-domain) после RefreshScope");
        };
    }
}
