package space.br1440.platform.tracing.core.mdc.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

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
        RemoteServiceMdc.clearForTrace(TRACE_ID);
    }

    @Test
    void mdc_имеет_приоритет_над_contributors() {
        RemoteServiceMdc.putIfPresent("from-mirror", TRACE_ID);
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "from-mdc");

        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(List.of(() -> Optional.of("from-source")));

        assertThat(resolver.resolve()).contains("from-mdc");
    }

    @Test
    void contributor_цепочка_возвращает_первое_непустое() {
        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(List.of(
                () -> Optional.empty(),
                () -> Optional.of("second"),
                () -> Optional.of("third")));

        assertThat(resolver.resolve()).contains("second");
    }

    @Test
    void mirror_fallback_когда_MDC_и_contributors_пусты() {
        RemoteServiceMdc.putIfPresent("upstream-from-mirror", TRACE_ID);

        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(List.of());

        assertThat(resolver.resolve(TRACE_ID)).contains("upstream-from-mirror");
    }

    @Test
    void resolve_без_traceId_не_смотрит_в_mirror() {
        RemoteServiceMdc.putIfPresent("upstream-from-mirror", TRACE_ID);
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
    void constructor_делает_immutable_copy() {
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
    void пустой_optional_когда_ничего_не_найдено() {
        assertThat(new RemoteServiceNameResolver(List.of()).resolve()).isEmpty();
    }
}
