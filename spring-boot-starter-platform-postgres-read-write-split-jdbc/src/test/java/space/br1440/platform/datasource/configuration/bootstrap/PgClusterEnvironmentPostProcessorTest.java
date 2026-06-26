package space.br1440.platform.datasource.postgres.internal.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.exception.PostgresClusterConfigurationException;
import space.br1440.platform.datasource.postgres.PgClusterEnvironmentPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Level 1 — прямой вызов {@code postProcessEnvironment()}, без Spring-контекста.
 */
@DisplayName("PgClusterEnvironmentPostProcessor — canonical mapping + syntactic fail-fast")
class PgClusterEnvironmentPostProcessorTest {

    private final PgClusterEnvironmentPostProcessor epp =
            new PgClusterEnvironmentPostProcessor(new TestDeferredLogFactory());

    @Test
    @DisplayName("нет PG_* → no-op, property-source не регистрируется")
    void noPgEnv_isNoOp() {
        MockEnvironment env = new MockEnvironment();

        epp.postProcessEnvironment(env, app());

        assertThat(env.getPropertySources().contains(PgPropertySourceRegistrar.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("EPP НЕ зависит от routing-mode: маппит PG_* даже без PG_ROUTING_MODE")
    void mapsEvenWithoutRoutingMode() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");

        epp.postProcessEnvironment(env, app());

        PropertySource<?> ps = source(env);
        assertThat(ps).isNotNull();
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".nodes[0].host")).isEqualTo("10.0.0.1");
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".nodes[0].port")).isEqualTo(5432);
    }

    @Test
    @DisplayName("полный набор PG_* → canonical ключи + lowest precedence")
    void fullEnvSet_mappedToCanonical() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_ROUTING_MODE", "CLIENT");
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432, 10.0.0.2:5432");
        env.setProperty("PG_DATABASE", "ballistics");
        env.setProperty("PG_USERNAME", "app");
        env.setProperty("PG_PASSWORD", "s3cret");
        env.setProperty("PG_CONNECTION_PROPERTIES", "applicationName=ballistics;sslmode=require");

        epp.postProcessEnvironment(env, app());

        PropertySource<?> ps = source(env);
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".datasource-routing-mode")).isEqualTo("CLIENT");
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".nodes[0].host")).isEqualTo("10.0.0.1");
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".nodes[1].host")).isEqualTo("10.0.0.2");
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".database")).isEqualTo("ballistics");
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".credentials.username")).isEqualTo("app");
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".credentials.password")).isEqualTo("s3cret");
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".jdbc.properties.applicationName")).isEqualTo("ballistics");
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".jdbc.properties.sslmode")).isEqualTo("require");

        List<String> names = env.getPropertySources().stream().map(PropertySource::getName).toList();
        assertThat(names.indexOf(PgPropertySourceRegistrar.PROPERTY_SOURCE_NAME))
                .isGreaterThan(names.indexOf("mockProperties"));
    }

    @Test
    @DisplayName("malformed PG_CLUSTER_NODES → fail-fast (syntactic)")
    void malformedNodes_failsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_CLUSTER_NODES", "not-a-node");

        assertThatThrownBy(() -> epp.postProcessEnvironment(env, app()))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("Malformed PG_CLUSTER_NODES");
    }

    @Test
    @DisplayName("forbidden key в PG_CONNECTION_PROPERTIES → fail-fast (rejected, не silent filter)")
    void forbiddenKey_failsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");
        env.setProperty("PG_CONNECTION_PROPERTIES", "user=hacker;applicationName=ok");

        assertThatThrownBy(() -> epp.postProcessEnvironment(env, app()))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("Forbidden key");
    }

    @Test
    @DisplayName("raw passwordless pair (PG_USERNAME без PG_PASSWORD) → fail-fast в EPP")
    void rawPasswordlessPair_failsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");
        env.setProperty("PG_USERNAME", "app");

        assertThatThrownBy(() -> epp.postProcessEnvironment(env, app()))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("must be set together");
    }

    @Test
    @DisplayName("EPP НЕ падает на отсутствие PG_DATABASE/PG_CLUSTER_NODES (semantic — зона Validator)")
    void missingDatabaseIsNotEppConcern() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_ROUTING_MODE", "CLIENT");

        epp.postProcessEnvironment(env, app());

        PropertySource<?> ps = source(env);
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".datasource-routing-mode")).isEqualTo("CLIENT");
    }

    @Test
    @DisplayName("getOrder() == HIGHEST_PRECEDENCE")
    void order_isHighest() {
        assertThat(epp.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("PG_SCHEMA → jdbc.properties.currentSchema в canonical property source")
    void pgSchema_mapsToCurrentSchema() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_SCHEMA", "my_schema");

        epp.postProcessEnvironment(env, app());

        assertThat(source(env).getProperty(PgClusterProperties.PREFIX + ".jdbc.properties.currentSchema"))
                .isEqualTo("my_schema");
    }

    @Test
    @DisplayName("PG_SCHEMA blank → currentSchema НЕ добавляется, property source не регистрируется")
    void pgSchemaBlank_noCurrentSchemaInCanonical() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_SCHEMA", "   ");

        epp.postProcessEnvironment(env, app());

        // Только PG_SCHEMA задан, и он blank → trimToNull → null → mapper не добавляет ключ → no-op
        assertThat(env.getPropertySources().contains(PgPropertySourceRegistrar.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("PG_SCHEMA + fullEnvSet → currentSchema присутствует в canonical")
    void fullEnvSet_withPgSchema_mapsCurrentSchema() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_ROUTING_MODE", "CLIENT");
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");
        env.setProperty("PG_DATABASE", "ballistics");
        env.setProperty("PG_USERNAME", "app");
        env.setProperty("PG_PASSWORD", "s3cret");
        env.setProperty("PG_SCHEMA", "ballistics_schema");

        epp.postProcessEnvironment(env, app());

        assertThat(source(env).getProperty(PgClusterProperties.PREFIX + ".jdbc.properties.currentSchema"))
                .isEqualTo("ballistics_schema");
    }

    @Test
    @DisplayName("PG_SCHEMA + currentSchema в PG_CONNECTION_PROPERTIES → не бросает исключение, PG_SCHEMA wins в EPP canonical")
    void pgSchema_conflictWithConnectionProperties_noExceptionAndPgSchemaWins() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");
        env.setProperty("PG_CONNECTION_PROPERTIES", "applicationName=svc;currentSchema=fromProps");
        env.setProperty("PG_SCHEMA", "fromEnv");

        // currentSchema в PG_CONNECTION_PROPERTIES разрешён (не fail-fast, только WARN в лог)
        assertThatCode(() -> epp.postProcessEnvironment(env, app())).doesNotThrowAnyException();

        // PG_SCHEMA wins в canonical EPP map
        assertThat(source(env).getProperty(PgClusterProperties.PREFIX + ".jdbc.properties.currentSchema"))
                .isEqualTo("fromEnv");
    }

    @Test
    @DisplayName("conflict-флаг __pgSchemaConflict не попадает в зарегистрированный property source")
    void conflictFlag_notLeakedIntoPropertySource() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");
        env.setProperty("PG_CONNECTION_PROPERTIES", "currentSchema=fromProps");
        env.setProperty("PG_SCHEMA", "fromEnv");

        epp.postProcessEnvironment(env, app());

        PropertySource<?> ps = source(env);
        assertThat(ps.getProperty(PgClusterProperties.PREFIX + ".__pgSchemaConflict")).isNull();
        assertThat(ps.containsProperty(PgClusterProperties.PREFIX + ".__pgSchemaConflict")).isFalse();
    }

    private static PropertySource<?> source(MockEnvironment env) {
        return env.getPropertySources().get(PgPropertySourceRegistrar.PROPERTY_SOURCE_NAME);
    }

    private static SpringApplication app() {
        return new SpringApplication();
    }

    private static final class TestDeferredLogFactory implements DeferredLogFactory {
        @Override
        public org.apache.commons.logging.Log getLog(java.util.function.Supplier<org.apache.commons.logging.Log> destination) {
            return destination.get();
        }
    }
}
