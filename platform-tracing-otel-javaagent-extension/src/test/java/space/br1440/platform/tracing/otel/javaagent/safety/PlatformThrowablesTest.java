package space.br1440.platform.tracing.otel.javaagent.safety;

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
}
