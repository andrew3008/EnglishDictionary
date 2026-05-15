package space.br1440.platform.datasource.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JdbcUrlBuilder")
class JdbcUrlBuilderTest {

    @Test
    void buildUrl_singleNode() {
        String url = JdbcUrlBuilder.buildUrl(
                "10.0.0.1:5432", "testdb", "primary", false, Map.of());

        assertThat(url).isEqualTo(
                "jdbc:postgresql://10.0.0.1:5432/testdb?targetServerType=primary");
    }

    @Test
    void buildUrl_multipleNodes_preferSecondary() {
        String url = JdbcUrlBuilder.buildUrl(
                "10.0.0.1:5432,10.0.0.2:5432", "mydb",
                "preferSecondary", false, Map.of());

        assertThat(url)
                .startsWith("jdbc:postgresql://10.0.0.1:5432,10.0.0.2:5432/mydb")
                .contains("targetServerType=preferSecondary")
                // currentSchema больше не управляется builder'ом — управление search_path
                // делегировано ORM/SQL-слою (Hibernate default_schema, явный SET search_path).
                .doesNotContain("currentSchema");
    }

    @Test
    void buildUrl_withExtraParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sslmode", "prefer");
        params.put("connectTimeout", "5");

        String url = JdbcUrlBuilder.buildUrl(
                "host1:5432", "db", "primary", false, params);

        assertThat(url)
                .contains("sslmode=prefer")
                .contains("connectTimeout=5");
    }

    @Test
    void buildUrl_invalidNodes_throws() {
        assertThatThrownBy(() ->
                JdbcUrlBuilder.buildUrl("invalid_format", "db", "primary", false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid PG_NODES format");
    }

    @Test
    void buildUrl_blankNodes_throws() {
        assertThatThrownBy(() ->
                JdbcUrlBuilder.buildUrl("", "db", "primary", false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pgNodes must not be blank");
    }

    @Test
    void buildUrl_blankDatabase_throws() {
        assertThatThrownBy(() ->
                JdbcUrlBuilder.buildUrl("host:5432", "", "primary", false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database must not be blank");
    }

    @Test
    void buildUrl_nullNodes_throws() {
        assertThatThrownBy(() ->
                JdbcUrlBuilder.buildUrl(null, "db", "primary", false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildUrl_hostnameWithDots_accepted() {
        String url = JdbcUrlBuilder.buildUrl(
                "pg-node-1.cluster.local:5432,pg-node-2.cluster.local:5432",
                "testdb", "primary", false, Map.of());

        assertThat(url).contains("pg-node-1.cluster.local:5432,pg-node-2.cluster.local:5432");
    }

    @Test
    void buildUrl_nullExtraParams_handledGracefully() {
        String url = JdbcUrlBuilder.buildUrl(
                "host:5432", "db", "primary", false, null);

        assertThat(url).isEqualTo(
                "jdbc:postgresql://host:5432/db?targetServerType=primary");
    }

    @Test
    @DisplayName("loadBalanced=true добавляет loadBalanceHosts=true в URL по явному флагу, не из map")
    void buildUrl_loadBalancedFlag_addsLoadBalanceHostsParam() {
        String url = JdbcUrlBuilder.buildUrl(
                "pg1:5432,pg2:5432", "db", "any", true, null);

        assertThat(url).contains("targetServerType=any");
        assertThat(url).contains("loadBalanceHosts=true");
    }
}
