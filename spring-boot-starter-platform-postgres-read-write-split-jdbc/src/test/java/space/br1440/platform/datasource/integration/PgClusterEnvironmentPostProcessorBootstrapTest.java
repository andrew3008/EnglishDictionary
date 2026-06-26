package space.br1440.platform.datasource.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import space.br1440.platform.datasource.postgres.PostgresDataSourceRoutingAutoConfiguration;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.internal.bootstrap.PgPropertySourceRegistrar;
import space.br1440.platform.datasource.postgres.internal.bootstrap.OnClientDataSourceRoutingCondition;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;
import space.br1440.platform.datasource.support.junit.ClusterRoutingItProperties;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap IT: {@code PG_*} env → auto-registered {@code PgClusterEnvironmentPostProcessor}
 * → canonical property source → {@link PgClusterProperties} binding → starter activation.
 *
 * <p>EPP здесь <b>не</b> вызывается вручную — только механизм Spring Boot
 * ({@code META-INF/spring.factories}).
 *
 * <p>Canonical keys {@code platform.datasource.postgres-cluster.*} намеренно
 * <b>не</b> задаются через {@code setDefaultProperties}, чтобы доказать вклад EPP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PgClusterEnvironmentPostProcessor — Spring Boot bootstrap (PG_* env)")
class PgClusterEnvironmentPostProcessorBootstrapTest {

    private static final String PG_NODES = "10.0.0.11:5432,10.0.0.12:5433";
    private static final String PG_DATABASE = "epp_bootstrap_db";
    private static final String PG_USERNAME = "epp_user";
    private static final String PG_PASSWORD = "epp_secret";
    private static final String PG_CONNECTION_PROPERTIES = "applicationName=epp-bootstrap-it;sslmode=prefer";

    private ConfigurableApplicationContext context;

    @BeforeAll
    void startContextFromPgEnvOnly() {
        ClusterRoutingItProperties.installPgEnvSystemProperties(
                PG_NODES, PG_DATABASE, PG_USERNAME, PG_PASSWORD, PG_CONNECTION_PROPERTIES);

        SpringApplication app = new SpringApplication(BootstrapApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        Map<String, Object> tuningOnly = new HashMap<>();
        tuningOnly.put("spring.main.banner-mode", "off");
        tuningOnly.put(PgClusterProperties.PREFIX + ".pool.write.connection-timeout", "250");
        tuningOnly.put(PgClusterProperties.PREFIX + ".pool.read.connection-timeout", "250");
        tuningOnly.put(PgClusterProperties.PREFIX + ".pool.write.initialization-fail-timeout", "-1");
        tuningOnly.put(PgClusterProperties.PREFIX + ".pool.read.initialization-fail-timeout", "-1");
        tuningOnly.put(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size", "1");
        tuningOnly.put(PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size", "1");

        app.setDefaultProperties(tuningOnly);
        this.context = app.run();
    }

    @AfterAll
    void stopContext() {
        if (context != null && context.isActive()) {
            context.close();
        }
        ClusterRoutingItProperties.clearPgEnvSystemProperties();
    }

    @Nested
    @DisplayName("EnvironmentPostProcessor")
    class EnvironmentPostProcessorLink {

        @Test
        @DisplayName("EPP auto-registered property source postgresClusterEnvDefaults")
        void eppRegisteredPropertySource() {
            ConfigurableEnvironment environment = context.getEnvironment();

            PropertySource<?> source = environment.getPropertySources()
                    .get(PgPropertySourceRegistrar.PROPERTY_SOURCE_NAME);

            assertThat(source)
                    .as("Spring Boot must invoke PgClusterEnvironmentPostProcessor from spring.factories")
                    .isNotNull();
        }

        @Test
        @DisplayName("PG_* mapped to canonical Environment keys")
        void pgEnvMappedToCanonicalKeys() {
            ConfigurableEnvironment environment = context.getEnvironment();
            String prefix = PgClusterProperties.PREFIX;

            assertThat(environment.getProperty(OnClientDataSourceRoutingCondition.PROPERTY))
                    .isEqualTo("CLIENT");
            assertThat(environment.getProperty(prefix + ".datasource-routing-mode"))
                    .isEqualTo("CLIENT");
            assertThat(environment.getProperty(prefix + ".database"))
                    .isEqualTo(PG_DATABASE);
            assertThat(environment.getProperty(prefix + ".credentials.username"))
                    .isEqualTo(PG_USERNAME);
            assertThat(environment.getProperty(prefix + ".credentials.password"))
                    .isEqualTo(PG_PASSWORD);
            assertThat(environment.getProperty(prefix + ".nodes[0].host"))
                    .isEqualTo("10.0.0.11");
            assertThat(environment.getProperty(prefix + ".nodes[0].port", Integer.class))
                    .isEqualTo(5432);
            assertThat(environment.getProperty(prefix + ".nodes[1].host"))
                    .isEqualTo("10.0.0.12");
            assertThat(environment.getProperty(prefix + ".nodes[1].port", Integer.class))
                    .isEqualTo(5433);
            assertThat(environment.getProperty(prefix + ".jdbc.properties.applicationName"))
                    .isEqualTo("epp-bootstrap-it");
            assertThat(environment.getProperty(prefix + ".jdbc.properties.sslmode"))
                    .isEqualTo("prefer");
        }
    }

    @Nested
    @DisplayName("ConfigurationProperties + auto-configuration")
    class BindingAndAutoConfigurationLink {

        @Test
        @DisplayName("PgClusterProperties binds values originating from PG_* env")
        void pgClusterPropertiesBoundFromEppDefaults() {
            PgClusterProperties properties = context.getBean(PgClusterProperties.class);

            assertThat(properties.database()).isEqualTo(PG_DATABASE);
            assertThat(properties.credentials().username()).isEqualTo(PG_USERNAME);
            assertThat(properties.credentials().password()).isEqualTo(PG_PASSWORD);
            assertThat(properties.nodes())
                    .extracting(PgNode::toHostPort)
                    .containsExactly("10.0.0.11:5432", "10.0.0.12:5433");
            assertThat(properties.jdbc().properties())
                    .containsEntry("applicationName", "epp-bootstrap-it")
                    .containsEntry("sslmode", "prefer");
        }

        @Test
        @DisplayName("Starter auto-configuration activated from EPP-provided CLIENT mode")
        void routingAutoConfigurationActivated() {
            assertThat(context.getBeansOfType(RoutingDataSource.class)).hasSize(1);
            assertThat(context.containsBean("writeDataSource")).isTrue();
            assertThat(context.containsBean("readDataSource")).isTrue();
        }
    }

    @SpringBootConfiguration
    @ComponentScan(basePackageClasses = BootstrapApp.class, useDefaultFilters = false)
    @Import(PostgresDataSourceRoutingAutoConfiguration.class)
    static class BootstrapApp {
    }
}
