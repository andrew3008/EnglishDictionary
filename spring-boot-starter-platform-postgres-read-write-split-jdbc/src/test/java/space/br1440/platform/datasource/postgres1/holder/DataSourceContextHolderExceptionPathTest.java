package space.br1440.platform.datasource.postgres1.holder;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import space.br1440.platform.datasource.postgres.internal.proxy.aop.NonTransactionalRoutingAspect;
import space.br1440.platform.datasource.support.RoutingContextExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Проверка очистки {@link DataSourceContextHolder} при исключениях на пути {@code @UseReadReplica}.
 */
@DisplayName("DataSourceContextHolder - exception path (@UseReadReplica)")
@ExtendWith(RoutingContextExtension.class)
class DataSourceContextHolderExceptionPathTest {

    private static final int ITERATIONS = 1_000;

    private final NonTransactionalRoutingAspect aspect = new NonTransactionalRoutingAspect();

    @Test
    @DisplayName("исключение в @UseReadReplica не оставляет контекст в ThreadLocal")
    void useReadReplicaException_clearsContext() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenThrow(new IllegalStateException("simulated failure"));

        assertThatThrownBy(() -> aspect.routeForRead(pjp))
                .isInstanceOf(IllegalStateException.class);

        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    @DisplayName("многократные exception/success циклы не накапливают контекст")
    void repeatedExceptionCycles_doNotLeakContext() throws Throwable {
        for (int i = 0; i < ITERATIONS; i++) {
            final int iteration = i;
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            boolean shouldFail = iteration % 3 == 0;

            if (shouldFail) {
                when(pjp.proceed()).thenThrow(new RuntimeException("tick-" + iteration));
                assertThatThrownBy(() -> aspect.routeForRead(pjp))
                        .isInstanceOf(RuntimeException.class);
            } else {
                when(pjp.proceed()).thenAnswer(inv -> {
                    assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
                    return "ok-" + iteration;
                });
                aspect.routeForRead(pjp);
            }

            assertThat(DataSourceContextHolder.currentIfSet())
                    .as("iteration %d must leave holder empty", iteration)
                    .isNull();
        }
    }
}