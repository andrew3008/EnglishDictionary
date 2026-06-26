package space.br1440.platform.datasource.postgres.internal.pool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.properties.DataSourceRoutingMode;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties.Credentials;
import space.br1440.platform.datasource.postgres.api.exception.PostgresClusterConfigurationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PgClusterPropertiesValidator — semantic checks с actionable messages")
class PgClusterPropertiesValidatorTest {

    private final PgClusterPropertiesValidator validator = new PgClusterPropertiesValidator();

    private static PgClusterProperties props(DataSourceRoutingMode mode, List<PgNode> nodes,
                                             String database, Credentials credentials) {
        return new PgClusterProperties(mode, nodes, database, credentials, null, null, null, null);
    }

    @Test
    void nonClientMode_skipsValidation() {
        assertThatCode(() -> validator.validate(props(DataSourceRoutingMode.PROXY, List.of(), null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void clientMode_missingDatabase_failsWithActionableMessage() {
        assertThatThrownBy(() -> validator.validate(
                props(DataSourceRoutingMode.CLIENT, List.of(new PgNode("h", 5432)), null, null)))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("database")
                .extracting("action").asString().contains("PG_DATABASE");
    }

    @Test
    void clientMode_missingNodes_failsWithActionableMessage() {
        assertThatThrownBy(() -> validator.validate(
                props(DataSourceRoutingMode.CLIENT, List.of(), "orders", null)))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("node")
                .extracting("action").asString().contains("PG_CLUSTER_NODES");
    }

    @Test
    void clientMode_halfCredentials_fails() {
        assertThatThrownBy(() -> validator.validate(props(
                DataSourceRoutingMode.CLIENT, List.of(new PgNode("h", 5432)), "orders",
                new Credentials("app", null))))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("username and password must be set together");
    }

    @Test
    void clientMode_validFullConfig_passes() {
        assertThatCode(() -> validator.validate(props(
                DataSourceRoutingMode.CLIENT, List.of(new PgNode("h", 5432)), "orders",
                new Credentials("app", "secret"))))
                .doesNotThrowAnyException();
    }

    @Test
    void clientMode_passwordlessBothBlank_passes() {
        assertThatCode(() -> validator.validate(props(
                DataSourceRoutingMode.CLIENT, List.of(new PgNode("h", 5432)), "orders",
                new Credentials(null, null))))
                .doesNotThrowAnyException();
    }
}
