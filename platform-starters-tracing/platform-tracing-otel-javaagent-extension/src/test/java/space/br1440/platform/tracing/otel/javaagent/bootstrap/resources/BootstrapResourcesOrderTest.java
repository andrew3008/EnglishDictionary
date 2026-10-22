package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BootstrapResourcesOrderTest {

    @Test
    void resourcesCloseInStrictLifoOrder() {
        // given
        List<String> closeOrder = new ArrayList<>();
        BootstrapResources resources = new BootstrapResources();
        resources.push(() -> closeOrder.add("bottom"), "bottom");
        resources.push(() -> closeOrder.add("middle"), "middle");
        resources.push(() -> closeOrder.add("top"), "top");

        // when
        resources.close();

        // then
        assertThat(closeOrder).containsExactly("top", "middle", "bottom");
    }

    @Test
    void closeContinuesAfterFatalFailureAndRethrowsOriginalError() {
        // given
        List<String> closeOrder = new ArrayList<>();
        IllegalStateException runtimeFailure = new IllegalStateException("simulated lower cleanup failure");
        NoClassDefFoundError fatalFailure = new NoClassDefFoundError("simulated fatal cleanup failure");
        //noinspection resource — close() вызывается в тесте повторно и обязан бросить тот же отказ
        BootstrapResources resources = new BootstrapResources();
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
        assertThatThrownBy(resources::close).isSameAs(fatalFailure);
    }

    @Test
    void identicalFatalFailuresDoNotStopRemainingCleanupOrSelfSuppress() {
        // given
        List<String> closeOrder = new ArrayList<>();
        NoClassDefFoundError sharedFailure = new NoClassDefFoundError("shared fatal cleanup failure");
        //noinspection resource — close() вызывается в тесте повторно и обязан бросить тот же отказ
        BootstrapResources resources = new BootstrapResources();
        resources.push(() -> closeOrder.add("bottom"), "bottom");
        resources.push(() -> {
            closeOrder.add("middle");
            throw sharedFailure;
        }, "middle");
        resources.push(() -> {
            closeOrder.add("top");
            throw sharedFailure;
        }, "top");

        // when
        Throwable firstFailure = catchThrowable(resources::close);

        // then
        assertThat(firstFailure).isSameAs(sharedFailure);
        assertThat(sharedFailure.getSuppressed()).isEmpty();
        assertThat(closeOrder).containsExactly("top", "middle", "bottom");
        assertThatThrownBy(resources::close).isSameAs(sharedFailure);
    }

    @Test
    void repeatedCloseAfterFailureRethrowsSameInstance() {
        // given
        //noinspection resource — close() вызывается в тесте повторно и обязан бросить тот же отказ
        BootstrapResources resources = new BootstrapResources();
        resources.push(() -> {
            throw new IllegalStateException("simulated cleanup failure");
        }, "runtime-controls");

        // when
        Throwable firstFailure = catchThrowable(resources::close);

        // then
        assertThat(firstFailure).isInstanceOf(BootstrapResourceCleanupException.class);
        assertThatThrownBy(resources::close).isSameAs(firstFailure);
    }

    @Test
    void repeatedCloseAfterSuccessReturnsNormally() {
        // given
        BootstrapResources resources = new BootstrapResources();

        // when
        resources.close();

        // then
        assertThatCode(resources::close).doesNotThrowAnyException();
    }
}
