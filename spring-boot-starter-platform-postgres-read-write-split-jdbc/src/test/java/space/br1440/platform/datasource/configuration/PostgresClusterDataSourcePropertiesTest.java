package space.br1440.platform.datasource.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PostgresClusterDataSourceProperties — @ConfigurationProperties + JSR-303")
class PostgresClusterDataSourcePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(EnablePostgresClusterProps.class);

    @Test
    @DisplayName("Properties-1: полный набор properties → биндинг успешен, все поля прочитаны корректно")
    void fullPropertiesSet_isBoundCorrectly() {
        // Given
        runner.withPropertyValues(
                        "platform.datasource.postgres-cluster.nodes=10.0.0.1:5432,10.0.0.2:5432",
                        "platform.datasource.postgres-cluster.database=ballistics",
                        "platform.datasource.postgres-cluster.credentials.username=app",
                        "platform.datasource.postgres-cluster.credentials.password=s3cret",
                        "platform.datasource.postgres-cluster.connection-properties.applicationName=ballistics",
                        "platform.datasource.postgres-cluster.connection-properties.sslmode=require",
                        "platform.datasource.postgres-cluster.read-replica.strategy=SECONDARY_ONLY")
                // When
                .run(ctx -> {
                    PostgresClusterDataSourceProperties props = ctx.getBean(PostgresClusterDataSourceProperties.class);

                    // Then
                    assertThat(props.getNodes()).containsExactly("10.0.0.1:5432", "10.0.0.2:5432");
                    assertThat(props.getDatabase()).isEqualTo("ballistics");
                    assertThat(props.getCredentials().getUsername()).isEqualTo("app");
                    assertThat(props.getCredentials().getPassword()).isEqualTo("s3cret");
                    assertThat(props.getConnectionProperties())
                            .containsEntry("applicationName", "ballistics")
                            .containsEntry("sslmode", "require");
                    assertThat(props.getReadReplica().getStrategy())
                            .isEqualTo(PostgresClusterDataSourceProperties.ReadReplica.Strategy.SECONDARY_ONLY);
                });
    }

    @Test
    @DisplayName("Properties-2: дефолт стратегии чтения = LOAD_BALANCED")
    void defaultReadStrategy_isLoadBalanced() {
        // Given
        runner.withPropertyValues(
                        "platform.datasource.postgres-cluster.nodes=10.0.0.1:5432",
                        "platform.datasource.postgres-cluster.database=ballistics")
                // When
                .run(ctx -> {
                    PostgresClusterDataSourceProperties props = ctx.getBean(PostgresClusterDataSourceProperties.class);

                    // Then
                    assertThat(props.getReadReplica().getStrategy())
                            .isEqualTo(PostgresClusterDataSourceProperties.ReadReplica.Strategy.LOAD_BALANCED);
                });
    }

    @Test
    @DisplayName("Properties-3: пустой nodes → JSR-303 @NotEmpty не пропускает биндинг")
    void emptyNodes_failsJsr303() {
        // Given
        runner.withPropertyValues("platform.datasource.postgres-cluster.database=ballistics")
                // When
                .run(ctx -> {
                    // Then
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("nodes");
                });
    }

    @Test
    @DisplayName("Properties-4: пустой database → JSR-303 @NotBlank не пропускает биндинг")
    void blankDatabase_failsJsr303() {
        // Given
        runner.withPropertyValues(
                        "platform.datasource.postgres-cluster.nodes=10.0.0.1:5432",
                        "platform.datasource.postgres-cluster.database=")
                // When
                .run(ctx -> {
                    // Then
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("database");
                });
    }

    @Test
    @DisplayName("Properties-5: passwordless-режим (без credentials) → биндинг успешен (Credentials допускает null)")
    void passwordlessMode_isAllowed() {
        // Given
        runner.withPropertyValues(
                        "platform.datasource.postgres-cluster.nodes=10.0.0.1:5432",
                        "platform.datasource.postgres-cluster.database=ballistics")
                // When
                .run(ctx -> {
                    PostgresClusterDataSourceProperties props = ctx.getBean(PostgresClusterDataSourceProperties.class);

                    // Then
                    assertThat(props.getCredentials().getUsername()).isNull();
                    assertThat(props.getCredentials().getPassword()).isNull();
                });
    }

    @Test
    @DisplayName("Properties-6: префикс класса — публичная константа PREFIX")
    void prefix_constantMatchesAnnotation() {
        assertThat(PostgresClusterDataSourceProperties.PREFIX)
                .isEqualTo("platform.datasource.postgres-cluster");
    }

    @Configuration
    @EnableConfigurationProperties(PostgresClusterDataSourceProperties.class)
    static class EnablePostgresClusterProps { }
}
