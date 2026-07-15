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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformRemoteServiceNameProviderTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private final PlatformRemoteServiceNameProvider provider = new PlatformRemoteServiceNameProvider();

    @AfterEach
    void clearMdc() {
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

        Optional<String> result = provider.get();

        assertThat(result).contains("billing-service");
    }

    @Test
    void пустая_строка_в_MDC_трактуется_как_отсутствие_значения() {
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "  ");

        Optional<String> result = provider.get();

        assertThat(result).isEmpty();
    }

    @Test
    void метод_get_никогда_не_бросает_исключений() {
        assertThat(provider.get()).isEmpty();
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "ok");
        assertThat(provider.get()).contains("ok");
    }

    @Test
    void читает_trace_scoped_mirror_если_MDC_пуст() {
        RemoteServiceMdc.putIfPresent("upstream-from-mirror", TRACE_ID);

        Span span = Span.wrap(SpanContext.create(
                TRACE_ID, "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault()));
        try (var scope = span.makeCurrent()) {
            assertThat(provider.get()).contains("upstream-from-mirror");
        }
    }

    @Test
    void contributor_через_resolver() {
        PlatformRemoteServiceNameProvider withSource = new PlatformRemoteServiceNameProvider(
                new RemoteServiceNameResolver(java.util.List.of(() -> Optional.of("from-contributor"))));

        assertThat(withSource.get()).contains("from-contributor");
    }
}
