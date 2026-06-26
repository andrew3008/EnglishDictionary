package space.br1440.platform.datasource.postgres1.holder;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import space.br1440.platform.datasource.postgres.internal.proxy.aop.NonTransactionalRoutingAspect;
import space.br1440.platform.datasource.support.RoutingContextExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Утечки ThreadLocal при переиспользовании platform thread pool (типичный сценарий Tomcat/Jetty worker).
 */
@DisplayName("DataSourceContextHolder - platform thread reuse")
@ExtendWith(RoutingContextExtension.class)
class DataSourceContextHolderPlatformThreadReuseTest {

    private static final int POOL_SIZE = 4;
    private static final int TASK_COUNT = 10_000;

    private final NonTransactionalRoutingAspect aspect = new NonTransactionalRoutingAspect();

    @Test
    @DisplayName("4 platform-потока, 10k задач - контекст не переносится между задачами")
    void platformThreadPoolReuse_noContextLeak() throws InterruptedException, ExecutionException {
        AtomicInteger failures = new AtomicInteger();
        List<Future<Void>> futures = new ArrayList<>(TASK_COUNT);

        try (var executor = Executors.newFixedThreadPool(POOL_SIZE)) {
            for (int i = 0; i < TASK_COUNT; i++) {
                final int taskId = i;
                Callable<Void> task = () -> {
                    assertThat(DataSourceContextHolder.currentIfSet()).isNull();
                    if (taskId % 11 == 0) {
                        invokeWithException(taskId);
                    } else if (taskId % 13 == 0) {
                        invokeNestedWithException(taskId);
                    } else {
                        invokeSuccess(taskId);
                    }
                    assertThat(DataSourceContextHolder.currentIfSet()).isNull();
                    return null;
                };
                futures.add(executor.submit(task));
            }
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException ex) {
                    failures.incrementAndGet();
                }
            }
        }

        assertThat(failures.get()).isZero();
        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    private void invokeSuccess(int taskId) {
        try {
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            when(pjp.proceed()).thenAnswer(inv -> {
                assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
                return taskId;
            });
            aspect.routeForRead(pjp);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void invokeWithException(int taskId) {
        try {
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            when(pjp.proceed()).thenThrow(new RuntimeException("platform-fail-" + taskId));
            aspect.routeForRead(pjp);
        } catch (RuntimeException ex) {
            assertThat(ex.getMessage()).contains("platform-fail-" + taskId);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void invokeNestedWithException(int taskId) {
        try {
            ProceedingJoinPoint inner = mock(ProceedingJoinPoint.class);
            when(inner.proceed()).thenThrow(new RuntimeException("nested-platform-" + taskId));
            ProceedingJoinPoint outer = mock(ProceedingJoinPoint.class);
            when(outer.proceed()).thenAnswer(inv -> {
                try {
                    aspect.routeForRead(inner);
                } catch (RuntimeException ex) {
                    assertThat(ex.getMessage()).contains("nested-platform-" + taskId);
                }
                assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
                return taskId;
            });
            aspect.routeForRead(outer);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}