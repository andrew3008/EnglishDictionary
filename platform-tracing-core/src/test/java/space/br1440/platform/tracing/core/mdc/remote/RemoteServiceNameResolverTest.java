package space.br1440.platform.tracing.core.mdc.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteServiceNameResolverTest {

    private final RemoteServiceNameResolver resolver =
            new RemoteServiceNameResolver(List.of());

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void resolve_возвращает_из_mdc_с_наивысшим_приоритетом() {
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "inventory");
        RemoteServiceNameSource alwaysOther = () -> Optional.of("other-service");
        RemoteServiceNameResolver r = new RemoteServiceNameResolver(List.of(alwaysOther));

        assertThat(r.resolve()).contains("inventory");
    }

    @Test
    void resolve_использует_contributor_при_пустом_mdc() {
        RemoteServiceNameSource contributor = () -> Optional.of("catalog");
        RemoteServiceNameResolver r = new RemoteServiceNameResolver(List.of(contributor));

        assertThat(r.resolve()).contains("catalog");
    }

    @Test
    void resolve_использует_mirror_fallback_при_наличии_traceId() {
        RemoteServiceTraceMirror.put("trace-001", "billing");

        assertThat(resolver.resolve("trace-001")).contains("billing");

        RemoteServiceTraceMirror.clear("trace-001");
    }

    @Test
    void resolve_возвращает_empty_если_ничего_нет() {
        assertThat(resolver.resolve()).isEmpty();
        assertThat(resolver.resolve("no-such-trace")).isEmpty();
    }

    @Test
    void resolve_не_падает_если_contributor_бросает_исключение() {
        RemoteServiceNameSource broken = () -> { throw new RuntimeException("unavailable"); };
        RemoteServiceNameResolver r = new RemoteServiceNameResolver(List.of(broken));

        assertThat(r.resolve()).isEmpty();
    }

    @Test
    void resolve_перебирает_sources_в_порядке_и_возвращает_первый_непустой() {
        RemoteServiceNameSource empty = Optional::empty;
        RemoteServiceNameSource found = () -> Optional.of("shipping");
        RemoteServiceNameSource never = () -> Optional.of("SHOULD_NOT_REACH");
        RemoteServiceNameResolver r = new RemoteServiceNameResolver(List.of(empty, found, never));

        assertThat(r.resolve()).contains("shipping");
    }

    @Test
    void sources_list_is_immutable_copy() {
        var mutable = new java.util.ArrayList<RemoteServiceNameSource>();
        mutable.add(() -> Optional.of("first"));
        RemoteServiceNameResolver r = new RemoteServiceNameResolver(mutable);

        mutable.add(() -> Optional.of("injected-after"));

        // resolver не видит добавленный после создания элемент
        assertThat(r.resolve()).contains("first");
    }
}
