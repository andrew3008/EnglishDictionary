package space.br1440.platform.datasource.postgres.internal.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.internal.bootstrap.PgNodesParser;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.exception.PostgresClusterConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PgNodesParser — host:port CSV → List<PgNode>")
class PgNodesParserTest {

    @Test
    void single() {
        assertThat(PgNodesParser.parse("10.0.0.1:5432"))
                .containsExactly(new PgNode("10.0.0.1", 5432));
    }

    @Test
    void multi_withWhitespaceAndEmptySegments() {
        assertThat(PgNodesParser.parse(" 10.0.0.1:5432 ,, 10.0.0.2:5432 , "))
                .containsExactly(new PgNode("10.0.0.1", 5432), new PgNode("10.0.0.2", 5432));
    }

    @Test
    void ipv6_bracketed() {
        assertThat(PgNodesParser.parse("[::1]:5432"))
                .containsExactly(new PgNode("::1", 5432));
    }

    @Test
    void blank_throws() {
        assertThatThrownBy(() -> PgNodesParser.parse("   "))
                .isInstanceOf(PostgresClusterConfigurationException.class);
    }

    @Test
    void malformed_throws() {
        assertThatThrownBy(() -> PgNodesParser.parse("hostonly"))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("Malformed PG_CLUSTER_NODES");
    }

    @Test
    void nonNumericPort_throws() {
        assertThatThrownBy(() -> PgNodesParser.parse("host:abc"))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("not a number");
    }
}
