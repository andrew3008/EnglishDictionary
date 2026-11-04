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
        // given/when/then
        assertThatThrownBy(() -> PlatformThrowables.propagateIfFatal(new OutOfMemoryError("boom")))
                .isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void rethrows_LinkageError() {
        // given/when/then
        assertThatThrownBy(() -> PlatformThrowables.propagateIfFatal(new NoClassDefFoundError("x")))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void ordinary_exception_is_not_thrown() {
        // given/when/then
        assertThat(catchThrowable(() -> PlatformThrowables.propagateIfFatal(new RuntimeException("ordinary"))))
                .isNull();
    }

    @Test
    void interruptedException_restores_interrupt_flag() {
        // given
        Thread.interrupted();
        // when
        PlatformThrowables.propagateIfFatal(new InterruptedException("cancel"));
        // then
        assertThat(Thread.currentThread().isInterrupted())
                .as("Флаг прерывания должен быть восстановлен, чтобы не потерять сигнал")
                .isTrue();
        Thread.interrupted();
    }

    @Test
    void null_is_safe() {
        // given/when/then
        assertThat(catchThrowable(() -> PlatformThrowables.propagateIfFatal(null))).isNull();
    }

    @Test
    void propagateFatalCauseChain_rethrows_top_LinkageError() {
        // given/when/then
        assertThatThrownBy(() -> PlatformThrowables.propagateFatalCauseChain(new NoClassDefFoundError("company/Rule")))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void propagateFatalCauseChain_rethrows_LinkageError_wrapped_as_cause() {
        // given
        NoClassDefFoundError linkage = new NoClassDefFoundError("company/Rule");
        java.util.ServiceConfigurationError wrapper =
                new java.util.ServiceConfigurationError("provider init failed", linkage);

        // when/then
        assertThatThrownBy(() -> PlatformThrowables.propagateFatalCauseChain(wrapper))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void propagateFatalCauseChain_ordinary_exception_is_not_thrown() {
        // given/when/then
        assertThat(catchThrowable(
                () -> PlatformThrowables.propagateFatalCauseChain(new IllegalStateException("ordinary"))))
                .isNull();
    }

    @Test
    void propagateFatalCauseChain_self_referencing_cause_does_not_loop_forever() {
        // given
        RuntimeException selfCaused = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        // when/then
        assertThat(catchThrowable(() -> PlatformThrowables.propagateFatalCauseChain(selfCaused))).isNull();
    }

    @Test
    void firstFatalInCauseChain_returns_wrapped_fatal_by_identity() {
        // given
        LinkageError fatal = new LinkageError("SDK linkage failed");
        IllegalStateException wrapper = new IllegalStateException(
                "Startup failed", new RuntimeException("Customizer failed", fatal));

        // when
        Throwable selected = PlatformThrowables.firstFatalInCauseChain(wrapper);

        // then
        assertThat(selected).isSameAs(fatal);
    }

    @Test
    void firstFatalInCauseChain_restores_interrupt_for_direct_InterruptedException() {
        // given
        Thread.interrupted();
        InterruptedException interrupted = new InterruptedException("cancel");

        try {
            // when
            Throwable selected = PlatformThrowables.firstFatalInCauseChain(interrupted);

            // then
            assertThat(selected).isNull();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void firstFatalInCauseChain_restores_interrupt_for_wrapped_InterruptedException() {
        // given
        Thread.interrupted();
        IllegalStateException wrapper = new IllegalStateException(
                "Startup interrupted", new InterruptedException("cancel"));

        try {
            // when
            Throwable selected = PlatformThrowables.firstFatalInCauseChain(wrapper);

            // then
            assertThat(selected).isNull();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void firstFatalInCauseChain_stops_on_self_cycle() {
        // given
        RuntimeException selfCaused = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        // when
        Throwable selected = PlatformThrowables.firstFatalInCauseChain(selfCaused);

        // then
        assertThat(selected).isNull();
    }

    @Test
    void firstFatalInCauseChain_inspects_at_most_sixteen_chain_nodes() {
        // given
        LinkageError fatal = new LinkageError("SDK linkage failed");
        Throwable reachable = wrapToDepth(fatal, 15);
        Throwable unreachable = wrapToDepth(fatal, 16);

        // when
        Throwable reachableSelection = PlatformThrowables.firstFatalInCauseChain(reachable);
        Throwable unreachableSelection = PlatformThrowables.firstFatalInCauseChain(unreachable);

        // then
        assertThat(reachableSelection).isSameAs(fatal);
        assertThat(unreachableSelection).isNull();
    }

    @Test
    void firstFatalInCauseChain_ordinary_accessor_failure_ends_traversal_without_escaping() {
        // given
        RuntimeException failure = new RuntimeException("Startup failure with inaccessible cause") {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("Cause inspection failed");
            }
        };

        // when
        Throwable selected = PlatformThrowables.firstFatalInCauseChain(failure);

        // then
        assertThat(selected).isNull();
        assertThat(catchThrowable(() -> PlatformThrowables.propagateFatalCauseChain(failure))).isNull();
    }

    @Test
    void firstFatalInCauseChain_returns_fatal_accessor_failure_by_identity() {
        // given
        LinkageError accessorFailure = new LinkageError("Cause accessor linkage failed");
        RuntimeException failure = new RuntimeException("Startup failure with inaccessible cause") {
            @Override
            public synchronized Throwable getCause() {
                throw accessorFailure;
            }
        };

        // when
        Throwable selected = PlatformThrowables.firstFatalInCauseChain(failure);

        // then
        assertThat(selected).isSameAs(accessorFailure);
        assertThat(catchThrowable(() -> PlatformThrowables.propagateFatalCauseChain(failure))).isSameAs(accessorFailure);
    }

    private static Throwable wrapToDepth(Throwable fatal, int depth) {
        Throwable current = fatal;
        for (int index = 0; index < depth; index++) {
            current = new IllegalStateException("Wrapper " + index, current);
        }

        return current;
    }
}
