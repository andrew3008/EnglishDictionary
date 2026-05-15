package space.br1440.platform.datasource.postgres;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@DisplayName("DataSourceRoutingLifecycle")
class DataSourceRoutingLifecycleTest {

    @Test
    void start_setsRunning() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        lifecycle.start();

        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    void stop_clearsRunning() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        lifecycle.start();
        lifecycle.stop();

        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void stop_closesReadPoolBeforeWritePool() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        lifecycle.start();
        lifecycle.stop();

        InOrder inOrder = inOrder(read, write);
        inOrder.verify(read).close();
        inOrder.verify(write).close();
    }

    @Test
    void stop_nullDataSource_doesNotThrow() {
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(null, null);

        lifecycle.start();

        assertThatCode(lifecycle::stop).doesNotThrowAnyException();
    }

    @Test
    void stop_alreadyClosed_skipsClose() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        when(write.isClosed()).thenReturn(true);
        when(read.isClosed()).thenReturn(true);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        lifecycle.start();
        lifecycle.stop();

        verify(write, never()).close();
        verify(read, never()).close();
    }

    @Test
    void getPhase_returnsMaxValueMinusOne() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 1);
    }
}
