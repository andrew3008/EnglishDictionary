package space.br1440.platform.tracing.autoconfigure.servicename;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.core.mdc.remote.RemoteServiceMdc;
import space.br1440.platform.tracing.core.mdc.remote.RemoteServiceNameResolver;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformRemoteServiceNameProviderTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";

    private final PlatformRemoteServiceNameProvider provider =
            new PlatformRemoteServiceNameProvider(new RemoteServiceNameResolver(List.of()));

    @AfterEach
    void cleanup() {
        MDC.clear();
        RemoteServiceMdc.clearForTrace(TRACE_ID);
    }

    @Test
    void пустой_optional_когда_MDC_не_содержит_ключ() {
        assertThat(provider.get()).isEmpty();
    }

    @Test
    void читает_имя_upstream_сервиса_из_MDC() {
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "billing-service");

        assertThat(provider.get()).contains("billing-service");
    }

    @Test
    void пустая_строка_в_MDC_трактуется_как_отсутствие_значения() {
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "  ");

        assertThat(provider.get()).isEmpty();
    }

    @Test
    void метод_get_никогда_не_бросает_исключений() {
        assertThat(provider.get()).isEmpty();
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "ok");
        assertThat(provider.get()).contains("ok");
    }

    /**
     * Mirror-fallback: traceId извлекается из активного Span через safeCurrentTraceId(),
     * resolver вызывается с resolve(traceId) — mirror читается по явному ключу.
     */
    @Test
    void читает_trace_scoped_mirror_через_активный_span() {
        RemoteServiceMdc.putIfPresent("upstream-from-mirror", TRACE_ID);
        MDC.remove(TracingMdcKeys.REMOTE_SERVICE); // MDC пуст — должен сработать mirror

        Span span = Span.wrap(SpanContext.create(
                TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault()));
        try (var ignored = span.makeCurrent()) {
            assertThat(provider.get()).contains("upstream-from-mirror");
        }
    }

    @Test
    void без_активного_span_mirror_не_читается() {
        RemoteServiceMdc.putIfPresent("upstream-from-mirror", TRACE_ID);
        MDC.remove(TracingMdcKeys.REMOTE_SERVICE);
        // Span.current() возвращает invalid — safeCurrentTraceId() вернёт null → resolve() без mirror

        assertThat(provider.get()).isEmpty();
    }

    @Test
    void contributor_через_resolver() {
        PlatformRemoteServiceNameProvider withSource = new PlatformRemoteServiceNameProvider(
                new RemoteServiceNameResolver(List.of(() -> Optional.of("from-contributor"))));

        assertThat(withSource.get()).contains("from-contributor");
    }
}
