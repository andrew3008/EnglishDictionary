package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class DiagnosticReadinessHandleTest {

    @Test
    void runtime_close_failure_is_rethrown_by_identity() {
        // given
        DiagnosticReadinessHandle handle = new DiagnosticReadinessHandle();
        IllegalStateException failure = new IllegalStateException("Diagnostic cleanup failed");
        handle.registerIfAbsent(() -> () -> {
            throw failure;
        });

        // when
        Throwable first = catchThrowable(handle::close);
        Throwable repeated = catchThrowable(handle::close);

        // then
        assertThat(first).isSameAs(failure);
        assertThat(repeated).isSameAs(failure);
    }

    @Test
    void fatal_close_failure_is_rethrown_by_identity() {
        // given
        DiagnosticReadinessHandle handle = new DiagnosticReadinessHandle();
        NoClassDefFoundError failure = new NoClassDefFoundError("Diagnostic cleanup failed");
        handle.registerIfAbsent(() -> () -> {
            throw failure;
        });

        // when
        Throwable first = catchThrowable(handle::close);
        Throwable repeated = catchThrowable(handle::close);

        // then
        assertThat(first).isSameAs(failure);
        assertThat(repeated).isSameAs(failure);
    }

    @Test
    void checked_close_failure_is_wrapped_once() {
        // given
        DiagnosticReadinessHandle handle = new DiagnosticReadinessHandle();
        Exception failure = new Exception("Diagnostic cleanup failed");
        handle.registerIfAbsent(() -> () -> {
            throw failure;
        });

        // when
        Throwable first = catchThrowable(handle::close);
        Throwable repeated = catchThrowable(handle::close);

        // then
        assertThat(first)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Diagnostic readiness MBean cleanup failed")
                .hasCause(failure);
        assertThat(repeated).isSameAs(first);
    }

    @Test
    void registrationAfterCloseIsIgnored() {
        // given
        DiagnosticReadinessHandle handle = new DiagnosticReadinessHandle();
        AtomicBoolean registrationClosed = new AtomicBoolean();
        AtomicBoolean lateFactoryInvoked = new AtomicBoolean();
        handle.registerIfAbsent(() -> () -> registrationClosed.set(true));

        // when
        handle.close();
        handle.registerIfAbsent(() -> {
            lateFactoryInvoked.set(true);
            return () -> {
            };
        });

        // then
        assertThat(registrationClosed).isTrue();
        assertThat(lateFactoryInvoked).isFalse();
    }
}
