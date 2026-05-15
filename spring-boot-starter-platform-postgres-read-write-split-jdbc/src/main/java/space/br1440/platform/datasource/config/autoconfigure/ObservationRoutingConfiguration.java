package space.br1440.platform.datasource.config.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.observation.HikariPoolNameExtractor;
import space.br1440.platform.datasource.observation.RoutingObservationConvention;
import space.br1440.platform.datasource.observation.RoutingObservationInterceptor;

/**
 * Регистрирует компоненты Micrometer Observation API для распределённого трейсинга маршрутизатора,
 * если {@link ObservationRegistry} доступен и зарегистрирован в контексте.
 *
 * <p>Передаёт {@link HikariPoolNameExtractor#INSTANCE} в {@link RoutingObservationInterceptor}
 * как callback извлечения имени пула: ядро остаётся транспорт-агностичным, а детали
 * {@code HikariDataSource.getPoolName()} локализованы в JDBC-стартере.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.micrometer.observation.ObservationRegistry")
@ConditionalOnBean(ObservationRegistry.class)
public class ObservationRoutingConfiguration {

    @Bean
    @ConditionalOnMissingBean(RoutingObservationConvention.class)
    public RoutingObservationConvention routingObservationConvention() {
        return new RoutingObservationConvention.Default();
    }

    @Bean
    @ConditionalOnMissingBean(RoutingObservationInterceptor.class)
    public RoutingObservationInterceptor routingObservationInterceptor(ObservationRegistry registry,
                                                                       RoutingObservationConvention convention) {
        return new RoutingObservationInterceptor(registry, convention, HikariPoolNameExtractor.INSTANCE);
    }
}
