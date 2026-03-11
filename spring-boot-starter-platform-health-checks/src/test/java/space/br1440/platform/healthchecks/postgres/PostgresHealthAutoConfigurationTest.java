package space.br1440.platform.healthchecks.postgres;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.healthchecks.configuration.PostgresHealthAutoConfiguration;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("PostgresHealthAutoConfiguration: auto-configuration")
class PostgresHealthAutoConfigurationTest {

    private final DataSource mockDataSource = mock(DataSource.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PostgresHealthAutoConfiguration.class));

    private final ApplicationContextRunner contextRunnerWithDataSource = contextRunner
            .withBean(DataSource.class, () -> mockDataSource);

    @Nested
    @DisplayName("@ConditionalOnClass — DataSource.class")
    class ConditionalOnDataSourceClass {

        @Test
        @DisplayName("#1 DataSource класс отсутствует в classpath → 0 бинов автоконфигурации")
        void shouldNotRegisterBeansWhenDataSourceClassMissing() {
            contextRunner
                    .withClassLoader(new FilteredClassLoader(DataSource.class))
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(PoolHealthEvaluator.class);
                        assertThat(ctx).doesNotHaveBean(PostgresHealthIndicator.class);
                    });
        }
    }

    @Nested
    @DisplayName("@ConditionalOnBean — DataSource bean")
    class ConditionalOnDataSourceBean {

        @Test
        @DisplayName("#2 DataSource класс есть, бина нет → PostgresHealthIndicator не регистрируется")
        void shouldNotRegisterHealthIndicatorWhenDataSourceBeanAbsent() {
            contextRunner.run(ctx ->
                    assertThat(ctx).doesNotHaveBean(PostgresHealthIndicator.class));
        }

        @Test
        @DisplayName("#2 PoolHealthEvaluator регистрируется даже без DataSource бина")
        void shouldRegisterPoolHealthEvaluatorWithoutDataSourceBean() {
            contextRunner.run(ctx ->
                    assertThat(ctx).hasSingleBean(PoolHealthEvaluator.class));
        }

        @Test
        @DisplayName("#3 DataSource бин присутствует → PoolHealthEvaluator + PostgresHealthIndicator зарегистрированы")
        void shouldRegisterAllBeansWhenDataSourceBeanPresent() {
            contextRunnerWithDataSource.run(ctx -> {
                assertThat(ctx).hasSingleBean(PoolHealthEvaluator.class);
                assertThat(ctx).hasSingleBean(PostgresHealthIndicator.class);
                assertThat(ctx).hasBean("postgresHealthIndicatorDelegate");
            });
        }
    }

    @Nested
    @DisplayName("platform.health.postgres.enabled")
    class EnabledProperty {

        @Test
        @DisplayName("#4 enabled=false → PostgresHealthIndicator не регистрируется")
        void shouldNotRegisterHealthIndicatorWhenPropertyDisabled() {
            contextRunnerWithDataSource
                    .withPropertyValues("platform.health.postgres.enabled=false")
                    .run(ctx ->
                            assertThat(ctx).doesNotHaveBean(PostgresHealthIndicator.class));
        }

        @Test
        @DisplayName("#5 enabled=true → PostgresHealthIndicator регистрируется")
        void shouldRegisterHealthIndicatorWhenPropertyEnabledExplicitly() {
            contextRunnerWithDataSource
                    .withPropertyValues("platform.health.postgres.enabled=true")
                    .run(ctx ->
                            assertThat(ctx).hasSingleBean(PostgresHealthIndicator.class));
        }

        @Test
        @DisplayName("#6 свойство не задано → matchIfMissing=true → PostgresHealthIndicator регистрируется")
        void shouldRegisterHealthIndicatorWhenPropertyMissing() {
            contextRunnerWithDataSource.run(ctx ->
                    assertThat(ctx).hasSingleBean(PostgresHealthIndicator.class));
        }
    }

    @Nested
    @DisplayName("@ConditionalOnMissingBean — пользовательские бины имеют приоритет")
    class UserDefinedBeansOverride {

        @Test
        @DisplayName("#7 пользовательский PoolHealthEvaluator → автоконфигурация не перезаписывает")
        void shouldNotOverrideUserDefinedPoolHealthEvaluator() {
            // Given
            PoolHealthEvaluator custom = mock(PoolHealthEvaluator.class);

            contextRunnerWithDataSource
                    .withBean(PoolHealthEvaluator.class, () -> custom)
                    .run(ctx -> {
                        // Then
                        assertThat(ctx).hasSingleBean(PoolHealthEvaluator.class);
                        assertThat(ctx.getBean(PoolHealthEvaluator.class)).isSameAs(custom);
                    });
        }

        @Test
        @DisplayName("#8 пользовательский PostgresHealthIndicator → автоконфигурация не перезаписывает")
        void shouldNotOverrideUserDefinedHealthIndicator() {
            // Given
            PostgresHealthIndicator custom = mock(PostgresHealthIndicator.class);

            contextRunnerWithDataSource
                    .withBean(PostgresHealthIndicator.class, () -> custom)
                    .run(ctx -> {
                        // Then
                        assertThat(ctx).hasSingleBean(PostgresHealthIndicator.class);
                        assertThat(ctx.getBean(PostgresHealthIndicator.class)).isSameAs(custom);
                    });
        }
    }

    @Nested
    @DisplayName("привязка PostgresHealthProperties из конфигурации")
    class PropertiesBinding {

        @Test
        @DisplayName("#9 platform.health.postgres.health-check-timeout=10s → properties.getHealthCheckTimeout()==10s")
        void shouldBindPropertiesCorrectly() {
            // Given
            contextRunnerWithDataSource
                    .withPropertyValues("platform.health.postgres.health-check-timeout=10s")
                    .run(ctx -> {
                        // When
                        PostgresHealthProperties props = ctx.getBean(PostgresHealthProperties.class);

                        // Then
                        assertThat(props.getHealthCheckTimeout()).isEqualTo(Duration.ofSeconds(10));
                    });
        }

        @Test
        @DisplayName("#10 дефолтные значения properties корректны")
        void shouldHaveCorrectDefaultPropertyValues() {
            contextRunnerWithDataSource.run(ctx -> {
                // When
                PostgresHealthProperties props = ctx.getBean(PostgresHealthProperties.class);
                PostgresHealthProperties.ConnectionPool pool = props.getConnectionPool();

                // Then
                assertThat(props.isEnabled()).isTrue();
                assertThat(props.getHealthCheckTimeout()).isEqualTo(Duration.ofSeconds(3));
                assertThat(pool.isMonitoringEnabled()).isTrue();
                assertThat(pool.getMinAvailablePercent()).isEqualTo(10);
                assertThat(pool.getMaxWaitingThreads()).isEqualTo(5);
            });
        }
    }
}
