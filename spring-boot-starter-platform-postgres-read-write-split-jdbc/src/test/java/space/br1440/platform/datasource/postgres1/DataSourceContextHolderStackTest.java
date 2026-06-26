package space.br1440.platform.datasource.postgres.api.routing;

import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.support.RoutingContextExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DataSourceContextHolder — стек, очистка, изоляция потоков")
@ExtendWith(RoutingContextExtension.class)
class DataSourceContextHolderStackTest {

    @Nested
    @DisplayName("стек-семантика")
    class StackSemantics {

        @Test
        @DisplayName("set() / clear() работают как push/pop")
        void shouldBehaveAsStack() {
            DataSourceContextHolder.set(DataSourceType.READ);
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);

            DataSourceContextHolder.set(DataSourceType.WRITE);
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.WRITE);

            DataSourceContextHolder.clear();
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);

            DataSourceContextHolder.clear();
            assertThat(DataSourceContextHolder.currentIfSet()).isNull();
        }

        @Test
        @DisplayName("clear() на пустом стеке — no-op, без NPE")
        void shouldClearEmptyStackSafely() {
            DataSourceContextHolder.clear();
            DataSourceContextHolder.clear();
            assertThat(DataSourceContextHolder.currentIfSet()).isNull();
        }

        @Test
        @DisplayName("Глубокая вложенность 16 уровней корректно разворачивается")
        void shouldHandleDeepNesting() {
            for (int i = 0; i < 16; i++) {
                DataSourceContextHolder.set(i % 2 == 0 ? DataSourceType.READ : DataSourceType.WRITE);
            }
            for (int i = 15; i >= 0; i--) {
                assertThat(DataSourceContextHolder.currentIfSet())
                        .isEqualTo(i % 2 == 0 ? DataSourceType.READ : DataSourceType.WRITE);
                DataSourceContextHolder.clear();
            }
            assertThat(DataSourceContextHolder.currentIfSet()).isNull();
        }
    }

    @Nested
    @DisplayName("изоляция потоков")
    class ThreadIsolation {

        @Test
        @DisplayName("стеки потоков независимы — не InheritableThreadLocal")
        void shouldNotShareStackBetweenThreads() throws Exception {
            DataSourceContextHolder.set(DataSourceType.READ);

            AtomicReference<DataSourceType> childView = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    childView.set(DataSourceContextHolder.currentIfSet());
                    latch.countDown();
                });
                latch.await();
            }

            assertThat(childView.get()).as("child thread sees its own (empty) stack").isNull();
            assertThat(DataSourceContextHolder.currentIfSet())
                    .as("parent thread keeps its READ context")
                    .isEqualTo(DataSourceType.READ);
        }
    }
}
