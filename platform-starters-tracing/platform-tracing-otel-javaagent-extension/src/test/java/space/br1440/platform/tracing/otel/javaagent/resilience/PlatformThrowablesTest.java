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
    void пробрасывает_VirtualMachineError() {
        assertThatThrownBy(() -> PlatformThrowables.propagateIfFatal(new OutOfMemoryError("boom")))
                .isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void пробрасывает_LinkageError() {
        assertThatThrownBy(() -> PlatformThrowables.propagateIfFatal(new NoClassDefFoundError("x")))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void обычное_исключение_не_бросается() {
        // propagateIfFatal не должен бросать для не-фатальных — обёртка сама подавит его.
        assertThat(catchThrowable(() -> PlatformThrowables.propagateIfFatal(new RuntimeException("ordinary"))))
                .isNull();
    }

    @Test
    void interruptedException_восстанавливает_флаг_прерывания() {
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
    void null_безопасен() {
        assertThat(catchThrowable(() -> PlatformThrowables.propagateIfFatal(null))).isNull();
    }

    @Test
    void propagateFatalCauseChain_пробрасывает_верхний_LinkageError() {
        assertThatThrownBy(() -> PlatformThrowables.propagateFatalCauseChain(new NoClassDefFoundError("company/Rule")))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void propagateFatalCauseChain_пробрасывает_LinkageError_обёрнутый_как_cause() {
        NoClassDefFoundError linkage = new NoClassDefFoundError("company/Rule");
        java.util.ServiceConfigurationError wrapper =
                new java.util.ServiceConfigurationError("provider init failed", linkage);

        assertThatThrownBy(() -> PlatformThrowables.propagateFatalCauseChain(wrapper))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void propagateFatalCauseChain_обычное_исключение_не_бросается() {
        assertThat(catchThrowable(
                () -> PlatformThrowables.propagateFatalCauseChain(new IllegalStateException("ordinary"))))
                .isNull();
    }

    @Test
    void propagateFatalCauseChain_self_referencing_cause_не_бесконечно_зацикливается() {
        RuntimeException selfCaused = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(catchThrowable(() -> PlatformThrowables.propagateFatalCauseChain(selfCaused))).isNull();
    }
}
