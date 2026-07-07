package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Критический регрессионный тест: проверяет корректное поведение {@link OtelSdkExtension} в
 * {@code @Nested}-блоках в режиме {@code CLASS}.
 *
 * <p>Контракт: один {@link OpenTelemetrySdk} на корневой класс, {@code exporter.reset()} перед
 * каждым тестом — независимо от глубины вложенности, и {@code @Nested}-блоки переиспользуют
 * тот же SDK, что и корневые тесты.
 */
class OtelSdkExtensionNestedTest {

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.classScope();

    private static final AtomicReference<OpenTelemetrySdk> sdkInRoot = new AtomicReference<>();
    private static final AtomicReference<OpenTelemetrySdk> sdkInNested = new AtomicReference<>();

    @Test
    void корневой_тест(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        sdkInRoot.set(sdk);
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
        Tracer tracer = sdk.getTracer("root");
        Span span = tracer.spanBuilder("root-op").startSpan();
        span.end();
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Nested
    class Внутри {

        @Test
        void вложенный_тест_видит_тот_же_SDK_и_пустой_exporter(
                OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
            sdkInNested.set(sdk);
            // CLASS-scope + reset перед каждым тестом: exporter пуст даже после корневого теста.
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
            Tracer tracer = sdk.getTracer("nested");
            Span span = tracer.spanBuilder("nested-op").startSpan();
            span.end();
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        }
    }

    @AfterAll
    static void sdk_тот_же_в_корне_и_во_вложенном_блоке() {
        assertThat(sdkInRoot.get()).isNotNull();
        assertThat(sdkInNested.get()).isNotNull();
        assertThat(sdkInRoot.get()).isSameAs(sdkInNested.get());
    }
}
