package space.br1440.platform.datasource.postgres.api.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PgNode — value object host:port")
class PgNodeTest {

    @Test
    void toHostPort_rendersHostColonPort() {
        assertThat(new PgNode("pg-1", 5432).toHostPort()).isEqualTo("pg-1:5432");
    }

    @Test
    void host_isStripped() {
        assertThat(new PgNode("  pg-1  ", 5432).host()).isEqualTo("pg-1");
    }

    @Test
    void blankHost_throws() {
        assertThatThrownBy(() -> new PgNode("   ", 5432))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host must not be blank");
    }

    @Test
    void portOutOfRange_throws() {
        assertThatThrownBy(() -> new PgNode("pg-1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port out of range");
        assertThatThrownBy(() -> new PgNode("pg-1", 70_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port out of range");
    }
}
