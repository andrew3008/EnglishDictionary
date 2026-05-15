package space.br1440.platform.datasource.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static space.br1440.platform.datasource.support.assertj.RoutingAssertions.assertThat;

/**
 * Демонстрирует fluent API {@link space.br1440.platform.datasource.support.assertj.HikariUrlAssert}
 * на реальных URL, генерируемых {@link JdbcUrlBuilder}.
 *
 * <p>{@code currentSchema} больше не управляется builder'ом — это ответственность ORM/SQL-слоя.
 * {@code loadBalanceHosts} тоже не задаётся через {@code extraParams}: это явный
 * булевый параметр {@link JdbcUrlBuilder#buildUrl}.
 */
@DisplayName("JdbcUrlBuilder × RoutingAssertions (fluent)")
class JdbcUrlBuilderFluentAssertTest {

    @Test
    @DisplayName("Multi-host URL: правильный hostList, database, params; loadBalanced=true как явный флаг")
    void shouldBuildMultiHostUrl() {
        String url = JdbcUrlBuilder.buildUrl(
                "10.0.0.1:5432,10.0.0.2:5432",
                "orderdb",
                "preferSecondary",
                true,
                Map.of());

        assertThat(url)
                .hasDatabase("orderdb")
                .hasHost("10.0.0.1:5432")
                .hasHost("10.0.0.2:5432")
                .hasHostCount(2)
                .hasTargetServerType("preferSecondary")
                .hasNoParam("currentSchema")
                .hasParam("loadBalanceHosts", "true")
                .hasNoParam("sslmode");
    }

    @Test
    @DisplayName("Write-пул по умолчанию: targetServerType=primary, без loadBalanceHosts и без currentSchema")
    void shouldBuildWritePoolUrlWithoutLoadBalance() {
        String url = JdbcUrlBuilder.buildUrl(
                "pg1:5432,pg2:5432",
                "appdb",
                "primary",
                false,
                Map.of());

        assertThat(url)
                .hasTargetServerType("primary")
                .hasNoParam("loadBalanceHosts")
                .hasNoParam("currentSchema");
    }

    @Test
    @DisplayName("Read-пул c LOAD_BALANCED: targetServerType=any, loadBalanceHosts=true (через явный флаг)")
    void shouldBuildReadPoolUrlForLoadBalanced() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("hostRecheckSeconds", "0");

        String url = JdbcUrlBuilder.buildUrl(
                "pg1:5432,pg2:5432",
                "appdb",
                "any",
                true,
                params);

        assertThat(url)
                .hasTargetServerType("any")
                .hasParam("loadBalanceHosts", "true")
                .hasParam("hostRecheckSeconds", "0");
    }
}
