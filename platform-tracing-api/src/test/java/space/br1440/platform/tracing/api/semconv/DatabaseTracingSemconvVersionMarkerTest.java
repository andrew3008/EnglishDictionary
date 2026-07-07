package space.br1440.platform.tracing.api.semconv;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.manual.DatabaseTracing;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTracingSemconvVersionMarkerTest {

    @Test
    void databaseTracing_isAnnotatedWithExpectedVersion() {
        DatabaseSemconvVersion annotation =
                DatabaseTracing.class.getAnnotation(DatabaseSemconvVersion.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("1.28.0");
    }
}
