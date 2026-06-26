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
 * Proverka vosstanovleniya stecka DataSourceContextHolder pri vlozhennyh @UseReadReplica
 * i isklyuchenii vo vnutrennem scope.
 */
@DisplayName("DataSourceContextHolder - nested exception path")
@ExtendWith(RoutingContextExtension.class)
class DataSourceContextHolderNestedExceptionPathTest {

    private final NonTransactionalRoutingAspect aspect = new NonTransactionalRoutingAspect();

    @Test
    @DisplayName("исключение во внутреннем @UseReadReplica восстанавливает внешний стек")
    void nestedUseReadReplicaException_restoresOuterStack() throws Throwable {
        ProceedingJoinPoint inner = mock(ProceedingJoinPoint.class);
        when(inner.proceed()).thenThrow(new IllegalArgumentException("inner boom"));

        ProceedingJoinPoint outer = mock(ProceedingJoinPoint.class);
        when(outer.proceed()).thenAnswer(inv -> {
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);

            assertThatThrownBy(() -> aspect.routeForRead(inner))
                    .isInstanceOf(IllegalArgumentException.class);

            // Внутренний finally снял только свой уровень - внешний READ сохранен
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
            return "outer-ok";
        });

        Object result = aspect.routeForRead(outer);

        assertThat(result).isEqualTo("outer-ok");
        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    @DisplayName("трехуровневая вложенность - успешный проход и исключение на среднем уровне")
    void tripleNestedException_restoresCorrectStack() throws Throwable {
        ProceedingJoinPoint level3 = mock(ProceedingJoinPoint.class);
        when(level3.proceed()).thenReturn("L3");

        ProceedingJoinPoint level2 = mock(ProceedingJoinPoint.class);
        when(level2.proceed()).thenAnswer(inv -> aspect.routeForRead(level3));

        ProceedingJoinPoint level1 = mock(ProceedingJoinPoint.class);
        when(level1.proceed()).thenAnswer(inv -> {
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
            return aspect.routeForRead(level2);
        });

        assertThat(aspect.routeForRead(level1)).isEqualTo("L3");
        assertThat(DataSourceContextHolder.currentIfSet()).isNull();

        ProceedingJoinPoint level2Fail = mock(ProceedingJoinPoint.class);
        when(level2Fail.proceed()).thenThrow(new RuntimeException("L2 fail"));

        ProceedingJoinPoint level1WithFail = mock(ProceedingJoinPoint.class);
        when(level1WithFail.proceed()).thenAnswer(inv -> {
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
            assertThatThrownBy(() -> aspect.routeForRead(level2Fail))
                    .isInstanceOf(RuntimeException.class);
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
            return "L1-after-fail";
        });

        assertThat(aspect.routeForRead(level1WithFail)).isEqualTo("L1-after-fail");
        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }
}