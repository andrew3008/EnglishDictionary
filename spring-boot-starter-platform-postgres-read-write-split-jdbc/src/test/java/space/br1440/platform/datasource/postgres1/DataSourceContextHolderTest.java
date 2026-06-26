package space.br1440.platform.datasource.postgres.api.routing;

import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.support.RoutingContextExtension;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DataSourceContextHolder")
@ExtendWith(RoutingContextExtension.class)
class DataSourceContextHolderTest {

    @Test
    void initialState_isNull() {
        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    void set_setsValue() {
        DataSourceContextHolder.set(DataSourceType.READ);
        assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
    }

    @Test
    void clear_removesValue() {
        DataSourceContextHolder.set(DataSourceType.WRITE);
        DataSourceContextHolder.clear();
        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    void threadIsolation() throws InterruptedException {
        DataSourceContextHolder.set(DataSourceType.READ);

        Thread otherThread = new Thread(() ->
                assertThat(DataSourceContextHolder.currentIfSet()).isNull());
        otherThread.start();
        otherThread.join();

        assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
    }
}
