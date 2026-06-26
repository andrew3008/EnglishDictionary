package space.br1440.platform.datasource.postgres.internal.proxy.aop;

import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import space.br1440.platform.datasource.support.RoutingContextExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NonTransactionalRoutingAspect")
@ExtendWith(RoutingContextExtension.class)
class NonTransactionalRoutingAspectTest {

    private final NonTransactionalRoutingAspect aspect = new NonTransactionalRoutingAspect();

    @Test
    void noTransaction_setsReadAndClearsAfterProceed() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenAnswer(inv -> {
            assertThat(DataSourceContextHolder.currentIfSet()).isEqualTo(DataSourceType.READ);
            return "result";
        });

        Object result = aspect.routeForRead(pjp);

        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
        assertThat(result).isEqualTo("result");
    }

    @Test
    void noTransaction_clearsContext_onException() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> aspect.routeForRead(pjp))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
    }

    @Test
    void activeTransaction_doesNotSetContext_proceedsDirectly() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature sig = mock(Signature.class);
        when(sig.toShortString()).thenReturn("TestService.query()");
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn("tx-result");

        TransactionSynchronizationManager.setActualTransactionActive(true);

        Object result = aspect.routeForRead(pjp);

        assertThat(DataSourceContextHolder.currentIfSet()).isNull();
        assertThat(result).isEqualTo("tx-result");
    }

    @Test
    void returnValue_passedThrough() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(42);

        Object result = aspect.routeForRead(pjp);

        assertThat(result).isEqualTo(42);
    }
}
