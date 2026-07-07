package space.br1440.platform.tracing.autoconfigure.servicename;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.RemoteServiceContextReaders;
import space.br1440.platform.tracing.api.mdc.RemoteServiceTraceMirror;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformRemoteServiceNameProviderTest {

    private final PlatformRemoteServiceNameProvider provider = new PlatformRemoteServiceNameProvider();

    @AfterEach
    void clearMdc() {
        MDC.clear();
        RemoteServiceTraceMirror.clear("trace-abc");
        RemoteServiceContextReaders.clearForTesting();
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
        RemoteServiceTraceMirror.put("trace-abc", "upstream-from-mirror");

        // Provider читает Span.current().traceId — без активного span mirror не найдётся;
        // проверяем fallback через зарегистрированный reader.
        RemoteServiceContextReaders.register(() -> RemoteServiceTraceMirror.get("trace-abc"));

        assertThat(provider.get()).contains("upstream-from-mirror");
    }
}
