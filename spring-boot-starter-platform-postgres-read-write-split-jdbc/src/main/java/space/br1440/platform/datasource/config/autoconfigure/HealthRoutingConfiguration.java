package space.br1440.platform.datasource.config.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.health.CqrsRoutingHealthIndicator;
import space.br1440.platform.datasource.postgres.RoutingDataSource;

/**
 * Регистрирует {@link CqrsRoutingHealthIndicator}, если на classpath доступен Actuator
 * ({@code HealthIndicator}).
 *
 * <p>Имя бина {@code routingHealthIndicator} <b>детерминирует</b> ключ в
 * {@code /actuator/health/{key}} — Spring Boot Actuator отбрасывает суффикс
 * {@code HealthIndicator} и использует оставшуюся часть имени бина как ключ.
 * Итоговый ключ: {@code routing}. Это видимое API для k8s liveness/readiness probes
 * и внешних мониторов.
 *
 * <p>Защита «создаваемся только если есть {@link RoutingDataSource}» реализована через
 * инъекцию {@code writeDataSource} / {@code readDataSource}: эти бины существуют
 * только когда {@link space.br1440.platform.datasource.config.JdbcCqrsRoutingAutoConfiguration}
 * активирована. Без них Spring не найдёт зависимости и метод не вызовется — поэтому
 * отдельный {@code @ConditionalOnBean(RoutingDataSource.class)} не нужен.
 * Это также обходит известную нестабильность {@code @ConditionalOnBean} на
 * {@code @Import}-конфигурациях: условие вычислялось раньше, чем
 * {@code RoutingDataSource} успевал зарегистрироваться.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
public class HealthRoutingConfiguration {

    @Bean("routingHealthIndicator")
    @ConditionalOnMissingBean(CqrsRoutingHealthIndicator.class)
    public CqrsRoutingHealthIndicator routingHealthIndicator(
            @Qualifier("writeDataSource") HikariDataSource writeDataSource,
            @Qualifier("readDataSource") HikariDataSource readDataSource) {
        return new CqrsRoutingHealthIndicator(writeDataSource, readDataSource);
    }
}
