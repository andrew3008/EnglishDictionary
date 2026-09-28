package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapResourcesOrderTest {

    private static final String SECRET_MARKER = "shutdown-completion-secret";

    @Test
    void readinessTransitionRunsBeforeRuntimeAndAnchorClosesLast() {
        // given
        List<String> closeOrder = new ArrayList<>();
        AtomicReference<String> readiness = new AtomicReference<>("READY");
        AtomicReference<Runnable> shutdownAction = new AtomicReference<>();
        try (BootstrapResources resources = new BootstrapResources((threadName, action) -> {
            shutdownAction.set(action);
            return () -> {
            };
        })) {
            resources.push(() -> {
                assertThat(readiness.get()).isEqualTo("FAILED");
                closeOrder.add("runtime");
            }, "runtime-batch");
            resources.push(() -> {
                readiness.set("FAILED");
                closeOrder.add("transition");
            }, "readiness-transition");
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
    }

    @Test
    void closeContinuesAfterFatalFailureAndRethrowsOriginalError() {
        // given
        List<String> closeOrder = new ArrayList<>();
        IllegalStateException runtimeFailure = new IllegalStateException("simulated lower cleanup failure");
        NoClassDefFoundError fatalFailure = new NoClassDefFoundError("simulated fatal cleanup failure");
        try (BootstrapResources resources = new BootstrapResources((threadName, action) -> () -> {
        })) {
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

    @Test
    @ResourceLock(Resources.SYSTEM_ERR)
    void shutdownCompletionFailurePreservesCleanupCauseAndUsesOnlyStructuredStderr() {
        // given
        AtomicReference<Runnable> shutdownAction = new AtomicReference<>();
        AtomicReference<Throwable> receivedCleanupFailure = new AtomicReference<>();
        IllegalArgumentException cleanupCause = new IllegalArgumentException("cleanup-" + SECRET_MARKER);
        UnsupportedOperationException completionFailure = new UnsupportedOperationException("completion-" + SECRET_MARKER);
        ByteArrayOutputStream rawStderr = new ByteArrayOutputStream();
        PrintStream previousStderr = System.err;
        PrintStream replacementStderr = new PrintStream(rawStderr, true, StandardCharsets.UTF_8);
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();

        try (BootstrapResources resources = new BootstrapResources((threadName, action) -> {
            shutdownAction.set(action);
            return () -> {
            };
        })) {
            resources.push(() -> {
                throw cleanupCause;
            }, "failing-resource");

            resources.installShutdownHook("test-shutdown", cleanupFailure -> {
                receivedCleanupFailure.set(cleanupFailure);
                throw completionFailure;
            });

            System.setErr(replacementStderr);

            // when
            assertThatCode(shutdownAction.get()::run).doesNotThrowAnyException();

            // then
            assertThat(receivedCleanupFailure.get())
                    .isInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .isSameAs(cleanupCause);

            List<CapturedLogEvent> failures = logs.events()
                    .stream()
                    .filter(event -> event.eventCode() == AgentEventCode.BOOT_CLASSIFIED_FAILURE)
                    .toList();
            assertThat(failures)
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.fields())
                                .hasSize(2)
                                .containsEntry("phase", "shutdown")
                                .containsEntry("resourceLabel", "shutdown-completion");
                        assertThat(event.throwableDigest())
                                .contains("caused by " + IllegalArgumentException.class.getName())
                                .contains("suppressed " + BootstrapResourceCleanupException.class.getName())
                                .contains("caused by " + UnsupportedOperationException.class.getName())
                                .doesNotContain(SECRET_MARKER);
                    });

            assertThat(rawStderr.toString(StandardCharsets.UTF_8)).isEmpty();
            assertThat(logs.rendered()).doesNotContain(SECRET_MARKER);
        } finally {
            System.setErr(previousStderr);
            replacementStderr.close();
            logs.restore();
        }
    }
}
