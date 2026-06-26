package space.br1440.platform.datasource.jooq;

import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.postgres.api.jooq.RoutingDSLContextFactory;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import space.br1440.platform.datasource.support.RoutingContextExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("RoutingDSLContextFactory")
@ExtendWith(RoutingContextExtension.class)
class RoutingDSLContextFactoryTest {

    private final DSLContext dsl = mock(DSLContext.class);
    private final RoutingDSLContextFactory factory = new RoutingDSLContextFactory(dsl);

    @Test
    void read_setsReadContext_returnsResult() {
        String result = factory.read(ctx -> {
            assertThat(ctx).isSameAs(dsl);
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
            return "data";
        });

        assertThat(result).isEqualTo("data");
        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    void read_clearsContext_onException() {
        assertThatThrownBy(() -> factory.read(ctx -> {
            throw new RuntimeException("read error");
        })).isInstanceOf(RuntimeException.class);

        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    void readVoid_setsReadContext() {
        factory.readVoid(ctx -> {
            assertThat(ctx).isSameAs(dsl);
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
        });

        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    void write_setsWriteContext_returnsResult() {
        Integer result = factory.write(ctx -> {
            assertThat(ctx).isSameAs(dsl);
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.WRITE);
            return 42;
        });

        assertThat(result).isEqualTo(42);
        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    void writeVoid_setsWriteContext() {
        factory.writeVoid(ctx -> {
            assertThat(ctx).isSameAs(dsl);
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.WRITE);
        });

        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    void write_clearsContext_onException() {
        assertThatThrownBy(() -> factory.write(ctx -> {
            throw new RuntimeException("write error");
        })).isInstanceOf(RuntimeException.class);

        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }
}
