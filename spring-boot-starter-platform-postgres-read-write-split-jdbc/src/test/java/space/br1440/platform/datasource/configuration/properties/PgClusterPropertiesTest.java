package space.br1440.platform.datasource.postgres.api.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Биндинг record-based {@link PgClusterProperties} через {@code @ConfigurationProperties}.
 *
 * <p>Semantic-валидация (required {@code database}/{@code nodes}, passwordless pair) вынесена
 * в {@code PgClusterPropertiesValidator} и тестируется отдельно — здесь проверяется только
 * корректность property-binding в неизменяемые record-типы.
 */
@DisplayName("PgClusterProperties — @ConfigurationProperties record binding")
class PgClusterPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(EnableProps.class);

    @Test
    @DisplayName("полный набор properties → биндинг успешен, все поля прочитаны корректно")
    void fullPropertiesSet_isBoundCorrectly() {
        runner.withPropertyValues(
                        pk("datasource-routing-mode=CLIENT"),
                        pk("nodes[0].host=10.0.0.1"),
                        pk("nodes[0].port=5432"),
                        pk("nodes[1].host=10.0.0.2"),
                        pk("nodes[1].port=5432"),
                        pk("database=ballistics"),
                        pk("credentials.username=app"),
                        pk("credentials.password=s3cret"),
                        pk("jdbc.properties.applicationName=ballistics"),
                        pk("jdbc.properties.sslmode=require"),
                        pk("read.mode=STRICT_SECONDARY"))
                .run(ctx -> {
                    PgClusterProperties props = ctx.getBean(PgClusterProperties.class);

                    assertThat(props.datasourceRoutingMode()).isEqualTo(DataSourceRoutingMode.CLIENT);
                    assertThat(props.nodes()).hasSize(2);
                    assertThat(props.nodes().get(0).toHostPort()).isEqualTo("10.0.0.1:5432");
                    assertThat(props.nodes().get(1).toHostPort()).isEqualTo("10.0.0.2:5432");
                    assertThat(props.database()).isEqualTo("ballistics");
                    assertThat(props.credentials().username()).isEqualTo("app");
                    assertThat(props.credentials().password()).isEqualTo("s3cret");
                    assertThat(props.jdbc().properties())
                            .containsEntry("applicationName", "ballistics")
                            .containsEntry("sslmode", "require");
                    assertThat(props.read().mode()).isEqualTo(ReadMode.STRICT_SECONDARY);
                });
    }

    @Test
    @DisplayName("дефолт read-режима = SECONDARY_PREFERRED")
    void defaultReadMode_isSecondaryPreferred() {
        runner.withPropertyValues(
                        pk("datasource-routing-mode=CLIENT"),
                        pk("nodes[0].host=10.0.0.1"),
                        pk("nodes[0].port=5432"),
                        pk("database=ballistics"))
                .run(ctx -> {
                    PgClusterProperties props = ctx.getBean(PgClusterProperties.class);
                    assertThat(props.read().mode()).isEqualTo(ReadMode.SECONDARY_PREFERRED);
                });
    }

    @Test
    @DisplayName("passwordless-режим (без credentials) → биндинг успешен, credentials пусты")
    void passwordlessMode_isAllowed() {
        runner.withPropertyValues(
                        pk("datasource-routing-mode=CLIENT"),
                        pk("nodes[0].host=10.0.0.1"),
                        pk("nodes[0].port=5432"),
                        pk("database=ballistics"))
                .run(ctx -> {
                    PgClusterProperties props = ctx.getBean(PgClusterProperties.class);
                    assertThat(props.credentials().username()).isNull();
                    assertThat(props.credentials().password()).isNull();
                });
    }

    @Test
    @DisplayName("префикс класса — публичная константа PREFIX")
    void prefix_constantMatchesAnnotation() {
        ConfigurationProperties annotation = PgClusterProperties.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation).isNotNull();
        assertThat(PgClusterProperties.PREFIX).isEqualTo(annotation.prefix());
    }

    private static String pk(String suffixAndValue) {
        return PgClusterProperties.PREFIX + "." + suffixAndValue;
    }

    @Configuration
    @EnableConfigurationProperties(PgClusterProperties.class)
    static class EnableProps { }
}
