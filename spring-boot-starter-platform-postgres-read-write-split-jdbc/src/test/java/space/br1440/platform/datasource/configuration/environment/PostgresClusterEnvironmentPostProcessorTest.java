package space.br1440.platform.datasource.configuration.environment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;
import space.br1440.platform.datasource.exception.PostgresClusterConfigurationException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PostgresClusterEnvironmentPostProcessor — ENV-дефолты и early fail-fast")
class PostgresClusterEnvironmentPostProcessorTest {

    private final PostgresClusterEnvironmentPostProcessor postProcessor = new PostgresClusterEnvironmentPostProcessor(new TestDeferredLogFactory());

    @Test
    @DisplayName("EPP-1: PG_TOPOLOGY не задана → no-op (ни property-source, ни exception)")
    void pgTopologyNotSet_isNoOp() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");

        // When
        postProcessor.postProcessEnvironment(env, mockApplication());

        // Then
        assertThat(env.getPropertySources().contains(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .as("property-source EPP-а не должен регистрироваться без PG_TOPOLOGY=DIRECT")
                .isFalse();
    }

    @Test
    @DisplayName("EPP-2: PG_TOPOLOGY=HAPROXY → no-op (ни property-source, ни exception)")
    void pgTopologyHaproxy_isNoOp() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "HAPROXY");
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");

        // When
        postProcessor.postProcessEnvironment(env, mockApplication());

        // Then
        assertThat(env.getPropertySources().contains(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .as("property-source EPP-а не должен регистрироваться при PG_TOPOLOGY=HAPROXY")
                .isFalse();
    }

    @Test
    @DisplayName("EPP-3: PG_TOPOLOGY=DIRECT + пустая PG_CLUSTER_NODES → fail-fast PostgresClusterConfigurationException")
    void pgTopologyDirect_blankNodes_failsFast() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "DIRECT");
        env.setProperty("PG_CLUSTER_NODES", "   ");

        // When / Then
        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(env, mockApplication()))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("PG_CLUSTER_NODES")
                .hasMessageContaining("missing or blank");
    }

    @Test
    @DisplayName("EPP-4: PG_TOPOLOGY=DIRECT отсутствует PG_CLUSTER_NODES → fail-fast PostgresClusterConfigurationException")
    void pgTopologyDirect_absentNodes_failsFast() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "DIRECT");

        // When / Then
        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(env, mockApplication()))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("PG_CLUSTER_NODES");
    }

    @Test
    @DisplayName("EPP-5: PG_TOPOLOGY=DIRECT + полный набор PG_* → property-source с lowest precedence + все ключи смаплены")
    void pgTopologyDirect_fullEnvSet_propertiesInjected() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "DIRECT");
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432, 10.0.0.2:5432");
        env.setProperty("PG_DATABASE", "ballistics");
        env.setProperty("PG_USERNAME", "app");
        env.setProperty("PG_PASSWORD", "s3cret");
        env.setProperty("PG_CONNECTION_PROPERTIES", "applicationName=ballistics;sslmode=require");

        // When
        postProcessor.postProcessEnvironment(env, mockApplication());

        // Then
        PropertySource<?> ps = env.getPropertySources().get(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        assertThat(ps).as("EPP должен зарегистрировать property-source").isNotNull();

        // Then
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.nodes"))
                .isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> nodes = (List<String>) ps.getProperty("platform.datasource.postgres-cluster.nodes");
        assertThat(nodes).containsExactly("10.0.0.1:5432", "10.0.0.2:5432");

        // Then
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.database")).isEqualTo("ballistics");
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.credentials.username")).isEqualTo("app");
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.credentials.password")).isEqualTo("s3cret");
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.applicationName")).isEqualTo("ballistics");
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.sslmode")).isEqualTo("require");

        // Then
        List<String> sourceNames = env.getPropertySources().stream().map(PropertySource::getName).toList();
        int eppIndex = sourceNames.indexOf(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        int mockIndex = sourceNames.indexOf("mockProperties");
        assertThat(eppIndex)
                .as("EPP должен иметь самый низкий приоритет (последний в списке)")
                .isGreaterThan(mockIndex);
    }

    @Test
    @DisplayName("EPP-6: connectionProperties сохраняют оригинальный регистр ключей (PostgreSQL ожидает camelCase)")
    void connectionProperties_preserveOriginalCase() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "DIRECT");
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");
        env.setProperty("PG_CONNECTION_PROPERTIES",
                "applicationName=ballistics;loadBalanceHosts=true;targetServerType=primary");

        // When
        postProcessor.postProcessEnvironment(env, mockApplication());

        PropertySource<?> ps = env.getPropertySources().get(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);

        // Then
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.applicationName"))
                .isEqualTo("ballistics");
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.loadBalanceHosts"))
                .isEqualTo("true");
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.targetServerType"))
                .isEqualTo("primary");

        // Then
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.applicationname"))
                .isNull();
    }

    @Test
    @DisplayName("EPP-7: connectionProperties исключают user/password (case-insensitive) — credentials идут отдельным каналом")
    void connectionProperties_excludeUserAndPassword() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "DIRECT");
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");
        env.setProperty("PG_CONNECTION_PROPERTIES", "user=hacker;PASSWORD=oops;applicationName=ok;Password=oops2");

        // When
        postProcessor.postProcessEnvironment(env, mockApplication());

        PropertySource<?> ps = env.getPropertySources().get(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);

        // Then
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.user")).isNull();
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.password")).isNull();
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.PASSWORD")).isNull();
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.Password")).isNull();

        // Then
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.applicationName"))
                .isEqualTo("ok");
    }

    @Test
    @DisplayName("EPP-8: connectionProperties с '=' в значении — алгоритм 'первый = как разделитель' (документация контракта)")
    void connectionProperties_valueContainsEquals_usesFirstEqualsAsDelimiter() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "DIRECT");
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");
        env.setProperty("PG_CONNECTION_PROPERTIES", "options=base64==payload");

        // When
        postProcessor.postProcessEnvironment(env, mockApplication());

        PropertySource<?> ps = env.getPropertySources().get(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);

        // Then
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.options"))
                .as("первый '=' разделитель → ключ 'options', значение 'base64==payload'")
                .isEqualTo("base64==payload");
    }

    @Test
    @DisplayName("EPP-9: PG_TOPOLOGY=direct (lowercase) — case-insensitive активация, всё работает так же")
    void pgTopologyDirect_lowercaseValue_isAccepted() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "direct");
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");

        // When
        postProcessor.postProcessEnvironment(env, mockApplication());

        // Then
        assertThat(env.getPropertySources().contains(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isTrue();
    }

    @Test
    @DisplayName("EPP-10: пустые/мусорные сегменты в PG_CLUSTER_NODES отфильтрованы; пустые connectionProperties-пары игнорируются")
    void parsing_isResilientToWhitespaceAndEmptySegments() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "DIRECT");
        env.setProperty("PG_CLUSTER_NODES", " 10.0.0.1:5432 ,, 10.0.0.2:5432 , ");
        env.setProperty("PG_CONNECTION_PROPERTIES", "applicationName=ballistics;;sslmode=require;=ignored;onlyKey=");

        // When
        postProcessor.postProcessEnvironment(env, mockApplication());

        PropertySource<?> ps = env.getPropertySources().get(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);

        // Then
        @SuppressWarnings("unchecked")
        List<String> nodes = (List<String>) ps.getProperty("platform.datasource.postgres-cluster.nodes");
        assertThat(nodes).containsExactly("10.0.0.1:5432", "10.0.0.2:5432");

        // Then
        Map<String, Object> connProps = Map.of(
                "applicationName", ps.getProperty("platform.datasource.postgres-cluster.connection-properties.applicationName"),
                "sslmode", ps.getProperty("platform.datasource.postgres-cluster.connection-properties.sslmode"),
                "onlyKey", ps.getProperty("platform.datasource.postgres-cluster.connection-properties.onlyKey")
        );
        assertThat(connProps.get("applicationName")).isEqualTo("ballistics");
        assertThat(connProps.get("sslmode")).isEqualTo("require");

        // Then
        assertThat(connProps.get("onlyKey")).isEqualTo("");
        assertThat(ps.getProperty("platform.datasource.postgres-cluster.connection-properties.")).isNull();
    }

    @Test
    @DisplayName("EPP-11: getOrder() == HIGHEST_PRECEDENCE — гарантирует ранний запуск до загрузки application.yml")
    void epp_isHighestPrecedence() {
        assertThat(postProcessor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("EPP-12: повторный postProcessEnvironment не дублирует property-source")
    void epp_isIdempotent() {
        // Given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PG_TOPOLOGY", "DIRECT");
        env.setProperty("PG_CLUSTER_NODES", "10.0.0.1:5432");

        // When
        postProcessor.postProcessEnvironment(env, mockApplication());
        postProcessor.postProcessEnvironment(env, mockApplication());

        // Then
        long count = env.getPropertySources().stream()
                .filter(ps -> PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME.equals(ps.getName()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    private SpringApplication mockApplication() {
        return new SpringApplication();
    }

    private static final class TestDeferredLogFactory implements DeferredLogFactory {

        @Override
        public org.apache.commons.logging.Log getLog(java.util.function.Supplier<org.apache.commons.logging.Log> destination) {
            return destination.get();
        }
    }
}
