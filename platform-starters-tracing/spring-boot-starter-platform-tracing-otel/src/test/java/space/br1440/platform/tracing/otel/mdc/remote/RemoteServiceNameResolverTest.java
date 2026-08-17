package space.br1440.platform.tracing.otel.mdc.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.runtime.remote.RemoteServiceTraceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteServiceNameResolverTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @AfterEach
    void clearMdc() {
        MDC.clear();
        RemoteServiceTraceContext.clear(TRACE_ID);
    }

    @Test
    void mdc_has_priority_over_contributors() {
        RemoteServiceTraceContext.record(TRACE_ID, "from-mirror");
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "from-mdc");

        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(List.of(() -> Optional.of("from-source")));

        assertThat(resolver.resolve()).contains("from-mdc");
    }

    @Test
    void contributor_chain_returns_first_non_blank() {
        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(List.of(
                () -> Optional.empty(),
                () -> Optional.of("second"),
                () -> Optional.of("third")));

        assertThat(resolver.resolve()).contains("second");
    }

    @Test
    void mirror_fallback_when_MDC_and_contributors_empty() {
        RemoteServiceTraceContext.record(TRACE_ID, "upstream-from-mirror");

        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(List.of());

        assertThat(resolver.resolve(TRACE_ID)).contains("upstream-from-mirror");
    }

    @Test
    void resolve_without_traceId_does_not_look_in_mirror() {
        RemoteServiceTraceContext.record(TRACE_ID, "upstream-from-mirror");
        MDC.remove(TracingMdcKeys.REMOTE_SERVICE);

        assertThat(new RemoteServiceNameResolver(List.of()).resolve()).isEmpty();
    }

    @Test
    void contributor_exception_fail_soft() {
        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(List.of(
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> Optional.of("recovered")));

        assertThat(resolver.resolve()).contains("recovered");
    }

    @Test
    void constructor_makes_immutable_copy() {
        List<RemoteServiceNameSource> mutable = new ArrayList<>();
        mutable.add(() -> Optional.of("first"));

        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(mutable);
        mutable.add(() -> Optional.of("second"));

        assertThat(resolver.resolve()).contains("first");
    }

    @Test
    void null_contributor_list_rejected() {
        assertThatThrownBy(() -> new RemoteServiceNameResolver(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void empty_optional_when_nothing_found() {
        assertThat(new RemoteServiceNameResolver(List.of()).resolve()).isEmpty();
    }
}
