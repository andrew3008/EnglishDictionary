package space.br1440.platform.tracing.otel.javaagent.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Контракт {@link PlatformThrowables#propagateIfFatal}: фатальные ошибки пробрасываются,
 * прерывание восстанавливает флаг, обычные исключения подавляются (не бросаются).
 */
class PlatformThrowablesTest {

    @Test
    void rethrows_VirtualMachineError() {
        assertThatThrownBy(() -> PlatformThrowables.propagateIfFatal(new OutOfMemoryError("boom")))
                .isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void rethrows_LinkageError() {
        assertThatThrownBy(() -> PlatformThrowables.propagateIfFatal(new NoClassDefFoundError("x")))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void ordinary_exception_is_not_thrown() {
        // propagateIfFatal не должен бросать для не-фатальных — обёртка сама подавит его.
        assertThat(catchThrowable(() -> PlatformThrowables.propagateIfFatal(new RuntimeException("ordinary"))))
                .isNull();
    }

    @Test
    void interruptedException_restores_interrupt_flag() {
        // Снимаем возможный ранее выставленный флаг.
        Thread.interrupted();
        PlatformThrowables.propagateIfFatal(new InterruptedException("cancel"));
        assertThat(Thread.currentThread().isInterrupted())
                .as("Флаг прерывания должен быть восстановлен, чтобы не потерять сигнал")
                .isTrue();
        // Очищаем, чтобы не повлиять на другие тесты.
        Thread.interrupted();
    }

    @Test
    void null_is_safe() {
        assertThat(catchThrowable(() -> PlatformThrowables.propagateIfFatal(null))).isNull();
    }

    @Test
    void propagateFatalCauseChain_rethrows_top_LinkageError() {
        assertThatThrownBy(() -> PlatformThrowables.propagateFatalCauseChain(new NoClassDefFoundError("company/Rule")))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void propagateFatalCauseChain_rethrows_LinkageError_wrapped_as_cause() {
        NoClassDefFoundError linkage = new NoClassDefFoundError("company/Rule");
        java.util.ServiceConfigurationError wrapper =
                new java.util.ServiceConfigurationError("provider init failed", linkage);

        assertThatThrownBy(() -> PlatformThrowables.propagateFatalCauseChain(wrapper))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void propagateFatalCauseChain_ordinary_exception_is_not_thrown() {
        assertThat(catchThrowable(
                () -> PlatformThrowables.propagateFatalCauseChain(new IllegalStateException("ordinary"))))
                .isNull();
    }

    @Test
    void propagateFatalCauseChain_self_referencing_cause_does_not_loop_forever() {
        RuntimeException selfCaused = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(catchThrowable(() -> PlatformThrowables.propagateFatalCauseChain(selfCaused))).isNull();
    }
}
