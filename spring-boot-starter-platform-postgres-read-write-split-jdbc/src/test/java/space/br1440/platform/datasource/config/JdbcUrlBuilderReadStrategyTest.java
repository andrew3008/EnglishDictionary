package space.br1440.platform.datasource.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link JdbcUrlBuilder}, проверяющие корректность построения JDBC URL
 * для сценариев read-стратегии LOAD_BALANCED ({@code targetServerType=any} +
 * {@code loadBalanceHosts=true}).
 *
 * <p>{@code loadBalanced} — явный булевый параметр {@code buildUrl}; ключ
 * {@code loadBalanceHosts} в {@code extraParams} больше не используется.
 */
class JdbcUrlBuilderReadStrategyTest {

    private static final String NODES = "10.0.0.1:5432,10.0.0.2:5432";
    private static final String DATABASE = "testdb";

    @Test
    void buildUrl_loadBalancedStrategy_includesTargetServerTypeAnyAndLoadBalanceHosts() {
        String url = JdbcUrlBuilder.buildUrl(NODES, DATABASE, "any", true, null);

        assertThat(url).startsWith("jdbc:postgresql://" + NODES + "/" + DATABASE + "?");
        assertThat(url).contains("targetServerType=any");
        assertThat(url).contains("loadBalanceHosts=true");
    }

    @Test
    void buildUrl_secondaryOnlyStrategy_keepsPreferSecondaryAndOmitsLoadBalance() {
        // SECONDARY_ONLY: targetServerType захардкожен в ReadPoolUrlStrategy как "preferSecondary",
        // loadBalanced=false — балансировки между хостами нет.
        String url = JdbcUrlBuilder.buildUrl(NODES, DATABASE, "preferSecondary", false, null);

        assertThat(url).contains("targetServerType=preferSecondary");
        assertThat(url).doesNotContain("loadBalanceHosts");
        assertThat(url).doesNotContain("targetServerType=any");
    }
}
