package space.br1440.platform.tracing.api.semconv;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.manual.DatabaseSpanBuilder;
import space.br1440.platform.tracing.api.semconv.annotation.DatabaseSemconvVersion;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSpanBuilderSemconvVersionMarkerTest {

    @Test
    void databaseSpanBuilder_isAnnotatedWithExpectedVersion() {
        DatabaseSemconvVersion annotation =
                DatabaseSpanBuilder.class.getAnnotation(DatabaseSemconvVersion.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("1.28.0");
    }
}
