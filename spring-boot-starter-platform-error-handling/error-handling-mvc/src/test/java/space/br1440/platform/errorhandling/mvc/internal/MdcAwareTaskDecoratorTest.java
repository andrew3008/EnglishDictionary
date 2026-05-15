package space.br1440.platform.errorhandling.mvc.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MdcAwareTaskDecorator")
class MdcAwareTaskDecoratorTest {

    private final MdcAwareTaskDecorator decorator = new MdcAwareTaskDecorator();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC из submitter-потока виден в дочернем при выполнении runnable")
    void mdcCopiedToChildThread() throws Exception {
        // given
        MDC.put("correlationId", "abc-123");
        AtomicReference<String> capturedCorrelation = new AtomicReference<>();

        Runnable wrapped = decorator.decorate(() -> capturedCorrelation.set(MDC.get("correlationId")));

        // when
        Thread child = new Thread(wrapped);
        child.start();
        child.join();

        // then
        assertThat(capturedCorrelation.get()).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("После выполнения runnable MDC дочернего потока возвращён в исходное состояние")
    void childThreadMdcRestoredAfterRun() throws Exception {
        // given
        MDC.put("correlationId", "outer");
        AtomicReference<String> mdcAtEnd = new AtomicReference<>();

        Runnable wrapped = decorator.decorate(() -> {
            // пустое тело: просто проверка состояния ПОСЛЕ через child Thread
        });

        Thread child = new Thread(() -> {
            // worker-thread начинается с собственного MDC (имитация reused-thread в pool)
            MDC.put("worker-key", "worker-value");
            try {
                wrapped.run();
            } finally {
                mdcAtEnd.set(MDC.get("worker-key"));
            }
        });
        child.start();
        child.join();

        // then — worker-key восстановлен после run
        assertThat(mdcAtEnd.get()).isEqualTo("worker-value");
    }

    @Test
    @DisplayName("Если у submitter MDC пуст — дочерний поток получает чистый MDC")
    void emptySubmitter_childGetsCleanMdc() throws Exception {
        // given
        MDC.clear();
        AtomicReference<String> captured = new AtomicReference<>("UNSET");

        Runnable wrapped = decorator.decorate(() -> captured.set(MDC.get("correlationId")));

        Thread child = new Thread(() -> {
            // у worker-thread что-то лежит — его не должно быть видно в нашей задаче
            MDC.put("correlationId", "stale-from-pool");
            wrapped.run();
        });
        child.start();
        child.join();

        // then — задача не видит "stale-from-pool", потому что snapshot был null → MDC.clear()
        assertThat(captured.get()).isNull();
    }
}
