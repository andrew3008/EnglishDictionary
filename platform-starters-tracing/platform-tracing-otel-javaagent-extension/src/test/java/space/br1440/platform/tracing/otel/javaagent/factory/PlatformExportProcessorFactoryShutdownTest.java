package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

import io.opentelemetry.sdk.common.CompletableResultCode;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformExportProcessorFactoryShutdownTest {

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    @Test
    void successful_shutdown_result_is_not_logged() {
        // given/when
        PlatformExportProcessorFactory.logUnsuccessfulStockShutdown(CompletableResultCode.ofSuccess());

        // then
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void failed_shutdown_result_is_logged_without_a_cause() {
        // given/when
        PlatformExportProcessorFactory.logUnsuccessfulStockShutdown(CompletableResultCode.ofFailure());

        // then
        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.eventName()).isEqualTo("STOCK_BATCH_SPAN_PROCESSOR_SHUTDOWN_UNSUCCESSFUL");
            assertThat(event.fields()).containsEntry("kind", "failed");
            assertThat(event.errorType()).isNull();
        });
    }

    @Test
    void unfinished_shutdown_result_is_logged_as_timeout() {
        // given/when
        PlatformExportProcessorFactory.logUnsuccessfulStockShutdown(new CompletableResultCode());

        // then
        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.fields()).containsEntry("kind", "timeout");
            assertThat(event.errorType()).isNull();
        });
    }
}
