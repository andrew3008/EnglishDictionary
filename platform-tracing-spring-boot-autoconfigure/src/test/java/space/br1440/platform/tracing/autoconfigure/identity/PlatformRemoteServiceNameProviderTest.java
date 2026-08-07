package space.br1440.platform.tracing.autoconfigure.identity;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceMdc;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceNameResolver;

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
    void empty_optional_when_MDC_lacks_key() {
        assertThat(provider.get()).isEmpty();
    }

    @Test
    void reads_upstream_service_name_from_MDC() {
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "billing-service");

        assertThat(provider.get()).contains("billing-service");
    }

    @Test
    void blank_MDC_value_treated_as_absent() {
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "  ");

        assertThat(provider.get()).isEmpty();
    }

    @Test
    void get_never_throws() {
        assertThat(provider.get()).isEmpty();
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "ok");
        assertThat(provider.get()).contains("ok");
    }

    /**
     * Mirror-fallback: traceId извлекается из активного Span через safeCurrentTraceId(),
     * resolver вызывается с resolve(traceId) — mirror читается по явному ключу.
     */
    @Test
    void reads_trace_scoped_mirror_via_active_span() {
        RemoteServiceMdc.putIfPresent("upstream-from-mirror", TRACE_ID);
        MDC.remove(TracingMdcKeys.REMOTE_SERVICE);

        Span span = Span.wrap(SpanContext.create(
                TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault()));
        try (var ignored = span.makeCurrent()) {
            assertThat(provider.get()).contains("upstream-from-mirror");
        }
    }

    /**
     * Без активного Span: safeCurrentTraceId() → null → resolve() без mirror-fallback.
     * <p>
     * MDC и contributors пусты — {@code isEmpty()} верно при любом пути; тест фиксирует
     * отсутствие NPE/падений (fail-soft §37), а не различие resolve() vs resolve(traceId).
     */
    @Test
    void without_active_span_mirror_is_not_read() {
        RemoteServiceMdc.putIfPresent("upstream-from-mirror", TRACE_ID);
        MDC.remove(TracingMdcKeys.REMOTE_SERVICE);

        assertThat(provider.get()).isEmpty();
    }

    @Test
    void contributor_via_resolver() {
        PlatformRemoteServiceNameProvider withSource = new PlatformRemoteServiceNameProvider(
                new RemoteServiceNameResolver(List.of(() -> Optional.of("from-contributor"))));

        assertThat(withSource.get()).contains("from-contributor");
    }
}
