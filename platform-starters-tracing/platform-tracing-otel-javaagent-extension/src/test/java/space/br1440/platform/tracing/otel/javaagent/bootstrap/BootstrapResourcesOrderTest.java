package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class BootstrapResourcesOrderTest {

    @Test
    void readinessTransitionRunsBeforeRuntimeAndAnchorClosesLast() {
        // given
        List<String> closeOrder = new ArrayList<>();
        AtomicReference<String> readiness = new AtomicReference<>("READY");
        AtomicReference<Runnable> shutdownAction = new AtomicReference<>();
        BootstrapResources resources = new BootstrapResources((threadName, action) -> {
            shutdownAction.set(action);
            return () -> {
            };
        });
        resources.push(() -> {
            assertThat(readiness.get()).isEqualTo("FAILED");
            closeOrder.add("runtime");
        }, "runtime-batch");
        ReadinessShutdownTransition transition = new ReadinessShutdownTransition(() -> {
            readiness.set("FAILED");
            closeOrder.add("transition");
        });
        resources.push(transition, "readiness-transition");
        assertThat(transition.arm()).isTrue();
        resources.installShutdownHook("test-shutdown", cleanupFailure -> {
            assertThat(cleanupFailure).isNull();
            assertThat(closeOrder).containsExactly("transition", "runtime");
            closeOrder.add("anchor");
        });

        // when
        shutdownAction.get().run();

        // then
        assertThat(closeOrder).containsExactly("transition", "runtime", "anchor");
    }

    @Test
    void unarmedTransitionIsNoOpDuringActivationRollback() {
        // given
        AtomicReference<String> readiness = new AtomicReference<>("INITIALIZING");
        ReadinessShutdownTransition transition = new ReadinessShutdownTransition(
                () -> readiness.set("FAILED")
        );

        // when
        transition.close();

        // then
        assertThat(readiness.get()).isEqualTo("INITIALIZING");
        assertThat(transition.arm()).isFalse();
    }

    @Test
    void closeContinuesAfterFatalFailureAndRethrowsOriginalError() {
        // given
        List<String> closeOrder = new ArrayList<>();
        IllegalStateException runtimeFailure = new IllegalStateException("simulated lower cleanup failure");
        NoClassDefFoundError fatalFailure = new NoClassDefFoundError("simulated fatal cleanup failure");
        BootstrapResources resources = new BootstrapResources((threadName, action) -> () -> {
        });
        resources.push(() -> {
            closeOrder.add("bottom");
            throw runtimeFailure;
        }, "bottom");
        resources.push(() -> {
            closeOrder.add("top");
            throw fatalFailure;
        }, "top");

        // when
        assertThatThrownBy(resources::close)
                .isSameAs(fatalFailure)
                .satisfies(failure -> {
                    assertThat(failure.getSuppressed()).hasSize(1);
                    assertThat(failure.getSuppressed()[0].getCause()).isSameAs(runtimeFailure);
                });

        // then
        assertThat(closeOrder).containsExactly("top", "bottom");
    }
}
