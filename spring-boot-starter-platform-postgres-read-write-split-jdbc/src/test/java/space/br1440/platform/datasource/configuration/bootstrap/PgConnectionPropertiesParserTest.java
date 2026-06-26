package space.br1440.platform.datasource.postgres.internal.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.exception.PostgresClusterConfigurationException;
import space.br1440.platform.datasource.postgres.internal.bootstrap.PgConnectionPropertiesParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PgConnectionPropertiesParser — k=v;k=v → Map с fail-fast на forbidden keys")
class PgConnectionPropertiesParserTest {

    @Test
    void empty_returnsEmpty() {
        assertThat(PgConnectionPropertiesParser.parse("")).isEmpty();
        assertThat(PgConnectionPropertiesParser.parse(null)).isEmpty();
    }

    @Test
    void multi_preservesKeyCaseAndOrder() {
        assertThat(PgConnectionPropertiesParser.parse("applicationName=svc;sslmode=require"))
                .containsEntry("applicationName", "svc")
                .containsEntry("sslmode", "require");
    }

    @Test
    void valueWithEquals_firstDelimiterWins() {
        assertThat(PgConnectionPropertiesParser.parse("options=base64==payload"))
                .containsEntry("options", "base64==payload");
    }

    @Test
    void emptySegmentsIgnored() {
        assertThat(PgConnectionPropertiesParser.parse("applicationName=svc;;sslmode=require"))
                .hasSize(2);
    }

    @Test
    void forbiddenUser_rejected() {
        assertThatThrownBy(() -> PgConnectionPropertiesParser.parse("user=hacker"))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("Forbidden key");
    }

    @Test
    void forbiddenPassword_caseInsensitive_rejected() {
        assertThatThrownBy(() -> PgConnectionPropertiesParser.parse("PASSWORD=oops"))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("Forbidden key");
    }

    @Test
    void malformedPair_rejected() {
        assertThatThrownBy(() -> PgConnectionPropertiesParser.parse("=novalue"))
                .isInstanceOf(PostgresClusterConfigurationException.class)
                .hasMessageContaining("Malformed");
    }
}
