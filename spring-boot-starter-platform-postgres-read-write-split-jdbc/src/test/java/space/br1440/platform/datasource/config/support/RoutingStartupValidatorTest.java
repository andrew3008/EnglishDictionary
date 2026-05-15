package space.br1440.platform.datasource.config.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.support.RoutingStartupValidator;
import space.br1440.platform.datasource.exception.PostgresClusterConfigurationException;
import space.br1440.platform.errorhandling.startup.PlatformConfigurationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверки secondary defence-in-depth-слоя {@link RoutingStartupValidator}.
 *
 * <p>Основной канал доставки конфиг-ошибок — адаптер стартера, читающий
 * environment-переменные {@code PG_CLUSTER_NODES} и {@code PG_DATABASE}.
 * Этот валидатор — fail-fast guard на случай programmatic-override DTO,
 * который забыл заполнить обязательные поля. Бросает domain-specific
 * {@link PostgresClusterConfigurationException} (наследник
 * {@link PlatformConfigurationException}) — для интеграции с
 * {@code PlatformConfigurationFailureAnalyzer}.
 */
@DisplayName("RoutingStartupValidator")
class RoutingStartupValidatorTest {

    private final RoutingStartupValidator validator = new RoutingStartupValidator();

    @Test
    @DisplayName("validate: валидные свойства — исключения нет")
    void validate_validProperties_passes() {
        PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
        props.setNodes(List.of("10.0.0.1:5432", "10.0.0.2:5432"));
        props.setDatabase("appdb");

        assertThatCode(() -> validator.validate(props)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validate: пустой nodes → PostgresClusterConfigurationException с канонической константой")
    void validate_emptyNodes_throwsWithCanonicalMessage() {
        PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
        props.setDatabase("appdb");

        assertThatThrownBy(() -> validator.validate(props))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .isInstanceOf(PlatformConfigurationException.class)
                .hasMessage(RoutingStartupValidator.MSG_NODES_MISSING);

        PostgresClusterConfigurationException ex = catchConfigEx(props);
        assertThat(ex.getAction()).isEqualTo(RoutingStartupValidator.ACTION_NODES_MISSING);
        // Регрессия на UX: ключевые токены, которые DevOps будет искать глазами в логах Kubernetes-пода.
        assertThat(ex.getAction()).contains("PG_CLUSTER_NODES");
    }

    @Test
    @DisplayName("validate: null nodes трактуется как отсутствующий")
    void validate_nullNodes_throws() {
        PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
        props.setNodes(null);
        props.setDatabase("appdb");

        assertThatThrownBy(() -> validator.validate(props))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessage(RoutingStartupValidator.MSG_NODES_MISSING);
    }

    @Test
    @DisplayName("validate: отсутствие database → PostgresClusterConfigurationException с канонической константой")
    void validate_missingDatabase_throwsWithCanonicalMessage() {
        PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
        props.setNodes(List.of("10.0.0.1:5432"));

        assertThatThrownBy(() -> validator.validate(props))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessage(RoutingStartupValidator.MSG_DATABASE_MISSING);

        PostgresClusterConfigurationException ex = catchConfigEx(props);
        assertThat(ex.getAction()).isEqualTo(RoutingStartupValidator.ACTION_DATABASE_MISSING);
        assertThat(ex.getAction()).contains("PG_DATABASE");
    }

    private PostgresClusterConfigurationException catchConfigEx(PostgresClusterDataSourceProperties props) {
        try {
            validator.validate(props);
            throw new AssertionError("expected PostgresClusterConfigurationException to be thrown");
        } catch (PostgresClusterConfigurationException ex) {
            return ex;
        }
    }
}
