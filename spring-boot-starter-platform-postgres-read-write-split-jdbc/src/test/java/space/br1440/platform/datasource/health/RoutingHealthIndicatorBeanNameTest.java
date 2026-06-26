package space.br1440.platform.datasource.postgres.internal.integration.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
    @DisplayName("bean is registered under name 'routingHealthIndicator'")
    void beanIsRegisteredUnderFixedName() {
        ctx = new AnnotationConfigApplicationContext();
        ctx.register(MinimalHealthConfig.class);
        ctx.refresh();

        assertThat(ctx.containsBean("routingHealthIndicator")).isTrue();
        assertThat(ctx.getBean("routingHealthIndicator")).isInstanceOf(DataSourceRoutingHealthIndicator.class);
    }

    @Configuration
    static class MinimalHealthConfig {

        @Bean("writeDataSource")
        DataSource writeDataSource() {
            return mock(DataSource.class);
        }

        @Bean("readDataSource")
        DataSource readDataSource() {
            return mock(DataSource.class);
        }

        @Bean
        RoutingHealthProperties routingHealthProperties() {
            return new RoutingHealthProperties(Duration.ofSeconds(5), Duration.ofSeconds(3));
        }

        @Bean("routingHealthIndicator")
        DataSourceRoutingHealthIndicator routingHealthIndicator(DataSource writeDataSource,
                                                                DataSource readDataSource,
                                                                RoutingHealthProperties healthProperties) {
            return new DataSourceRoutingHealthIndicator(
                    writeDataSource,
                    readDataSource,
                    space.br1440.platform.datasource.postgres.api.properties.ReadMode.SECONDARY_PREFERRED,
                    healthProperties);
        }
    }
}
