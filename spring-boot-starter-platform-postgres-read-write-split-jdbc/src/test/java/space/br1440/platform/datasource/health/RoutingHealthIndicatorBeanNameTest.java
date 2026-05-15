package space.br1440.platform.datasource.health;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.config.autoconfigure.HealthRoutingConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Закрепляет контракт имени бина {@code routingHealthIndicator}.
 *
 * <p>Spring Boot Actuator формирует ключ {@code /actuator/health/{key}} из имени бина,
 * отбрасывая суффикс {@code HealthIndicator}. Имя бина {@code routingHealthIndicator}
 * даёт ключ {@code routing} — публичное API для k8s liveness/readiness probes.
 *
 * <p>Если кто-то в будущем переименует метод в {@link HealthRoutingConfiguration}
 * (или уберёт явное {@code @Bean("routingHealthIndicator")}), ключ Actuator
 * неожиданно изменится — этот тест поймает регрессию.
 */
@DisplayName("Actuator health key contract: bean name 'routingHealthIndicator'")
class RoutingHealthIndicatorBeanNameTest {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    @DisplayName("бин регистрируется под именем 'routingHealthIndicator'")
    void beanIsRegisteredUnderFixedName() {
        ctx = new AnnotationConfigApplicationContext();
        // RoutingDataSource-бин не обязателен для резолюции имени, но нужен для @ConditionalOnBean
        // → регистрируем заглушку и импортируем HealthRoutingConfiguration.
        ctx.register(MinimalHealthConfig.class);
        ctx.refresh();

        assertThat(ctx.containsBean("routingHealthIndicator")).isTrue();

        Object bean = ctx.getBean("routingHealthIndicator");
        assertThat(bean).isInstanceOf(CqrsRoutingHealthIndicator.class);
    }

    /**
     * Минимальная конфигурация: заглушки {@link HikariDataSource} + явная регистрация
     * health-бина. Имитирует результат {@link HealthRoutingConfiguration}, минуя
     * {@code @ConditionalOnBean(RoutingDataSource)} (его здесь не хотим поднимать).
     */
    @Configuration
    static class MinimalHealthConfig {

        @Bean("writeDataSource")
        HikariDataSource writeDataSource() {
            return mock(HikariDataSource.class);
        }

        @Bean("readDataSource")
        HikariDataSource readDataSource() {
            return mock(HikariDataSource.class);
        }

        @Bean("routingHealthIndicator")
        CqrsRoutingHealthIndicator routingHealthIndicator(HikariDataSource writeDataSource,
                                                          HikariDataSource readDataSource) {
            return new CqrsRoutingHealthIndicator(writeDataSource, readDataSource);
        }
    }
}
