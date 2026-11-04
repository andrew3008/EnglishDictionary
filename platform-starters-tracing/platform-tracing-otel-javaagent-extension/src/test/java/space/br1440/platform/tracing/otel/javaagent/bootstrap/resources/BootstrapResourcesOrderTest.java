package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest(name = "assertion closes first: {0}")
    @ValueSource(booleans = {true, false})
    void linkageErrorBecomesRootOverAssertionErrorInEitherLifoOrder(boolean assertionClosesFirst) {
        // given
        AssertionError assertion = new AssertionError("simulated assertion cleanup failure");
        LinkageError linkage = new LinkageError("simulated linkage cleanup failure");
        //noinspection resource — close() вызывается в тесте повторно и обязан бросить тот же отказ
        BootstrapResources resources = new BootstrapResources();
        resources.push(() -> {
            throw assertionClosesFirst ? linkage : assertion;
        }, "bottom");
        resources.push(() -> {
            throw assertionClosesFirst ? assertion : linkage;
        }, "top");

        // when
        Throwable failure = catchThrowable(resources::close);

        // then
        assertThat(failure).isSameAs(linkage);
        assertThat(linkage.getSuppressed()).containsExactly(assertion);
        assertThatThrownBy(resources::close).isSameAs(linkage);
    }

    @Test
    void fatalCauseAccessorOfFirstFailureDoesNotStopRemainingCleanup() {
        // given
        List<String> closeOrder = new ArrayList<>();
        LinkageError accessorFailure = new LinkageError("simulated fatal cause inspection failure");
        OutOfMemoryError originalFatal = new ThrowingCauseOutOfMemoryError(accessorFailure);
        //noinspection resource — close() вызывается в тесте повторно и обязан бросить тот же отказ
        BootstrapResources resources = new BootstrapResources();
        resources.push(() -> closeOrder.add("bottom"), "bottom");
        resources.push(() -> {
            closeOrder.add("middle");
            throw new IllegalStateException("simulated middle cleanup failure");
        }, "middle");
        resources.push(() -> {
            closeOrder.add("top");
            throw originalFatal;
        }, "top");

        // when
        Throwable failure = catchThrowable(resources::close);

        // then
        assertThat(closeOrder).containsExactly("top", "middle", "bottom");
        assertThat(failure).isSameAs(originalFatal);
        assertThatThrownBy(resources::close).isSameAs(originalFatal);
    }

    @Test
    void ordinaryCauseAccessorOfFirstFailureDoesNotStopRemainingCleanup() {
        // given
        List<String> closeOrder = new ArrayList<>();
        LinkageError laterFatal = new LinkageError("simulated later fatal cleanup failure");
        //noinspection resource — close() вызывается в тесте повторно и обязан бросить тот же отказ
        BootstrapResources resources = new BootstrapResources();
        resources.push(() -> closeOrder.add("bottom"), "bottom");
        resources.push(() -> {
            closeOrder.add("middle");
            throw laterFatal;
        }, "middle");
        resources.push(() -> {
            closeOrder.add("top");
            throw new ThrowingCauseException();
        }, "top");

        // when
        Throwable failure = catchThrowable(resources::close);

        // then
        assertThat(closeOrder).containsExactly("top", "middle", "bottom");
        assertThat(failure).isSameAs(laterFatal);
        assertThat(laterFatal.getSuppressed()).isEmpty();
        assertThatThrownBy(resources::close).isSameAs(laterFatal);
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

    private static final class ThrowingCauseOutOfMemoryError extends OutOfMemoryError {

        private final transient LinkageError accessorFailure;

        private ThrowingCauseOutOfMemoryError(LinkageError accessorFailure) {
            super("simulated original fatal cleanup failure");
            this.accessorFailure = accessorFailure;
        }

        @Override
        public synchronized Throwable getCause() {
            throw accessorFailure;
        }
    }

    private static final class ThrowingCauseException extends IllegalStateException {

        private ThrowingCauseException() {
            super("simulated cleanup failure with broken cause accessor");
        }

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException("simulated cause inspection failure");
        }
    }
}
