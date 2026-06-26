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
 * Изоляция DataSourceContextHolder при virtual-thread-per-task (Java 21).
 */
@DisplayName("DataSourceContextHolder - virtual thread isolation")
@ExtendWith(RoutingContextExtension.class)
class DataSourceContextHolderVirtualThreadIsolationTest {

    private static final int TASK_COUNT = 10_000;

    private final NonTransactionalRoutingAspect aspect = new NonTransactionalRoutingAspect();

    @Test
    @DisplayName("10k VT-задач с exception/nested не оставляют контекст")
    void virtualThreadPerTask_noContextLeak() throws InterruptedException, ExecutionException {
        AtomicInteger failures = new AtomicInteger();
        List<Future<String>> futures = new ArrayList<>(TASK_COUNT);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < TASK_COUNT; i++) {
                final int taskId = i;
                Callable<String> task = () -> {
                    assertThat(DataSourceContextHolder.currentIfSet()).isNull();
                    if (taskId % 7 == 0) {
                        invokeNestedWithException(taskId);
                    } else if (taskId % 5 == 0) {
                        invokeWithException(taskId);
                    } else {
                        invokeSuccess(taskId);
                    }
                    assertThat(DataSourceContextHolder.currentIfSet()).isNull();
                    return "ok-" + taskId;
                };
                futures.add(executor.submit(task));
            }
            for (Future<String> future : futures) {
                try {
                    assertThat(future.get()).startsWith("ok-");
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
            when(pjp.proceed()).thenThrow(new RuntimeException("vt-fail-" + taskId));
            aspect.routeForRead(pjp);
        } catch (RuntimeException ex) {
            assertThat(ex.getMessage()).contains("vt-fail-" + taskId);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void invokeNestedWithException(int taskId) {
        try {
            ProceedingJoinPoint inner = mock(ProceedingJoinPoint.class);
            when(inner.proceed()).thenThrow(new RuntimeException("nested-vt-" + taskId));
            ProceedingJoinPoint outer = mock(ProceedingJoinPoint.class);
            when(outer.proceed()).thenAnswer(inv -> {
                try {
                    aspect.routeForRead(inner);
                } catch (RuntimeException ex) {
                    assertThat(ex.getMessage()).contains("nested-vt-" + taskId);
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