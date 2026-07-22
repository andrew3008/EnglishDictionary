package space.br1440.platform.tracing.otel.javaagent.configuration.spi;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.configuration.enums.QueueOverflowPolicy;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Тест живёт в том же пакете (.spi), что и тестируемые классы, чтобы иметь доступ
// к package-private OtelSdkDefaults и ExtensionEnvironmentVariables без расширения их видимости.
class PlatformTracingDefaultsProviderTest {

    @Test
    void supply_возвращает_все_BSP_дефолты() {
        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider().supply();

        // then
        assertThat(defaults)
                .containsEntry("otel.bsp.max.queue.size",
                        String.valueOf(OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE))
                .containsEntry("otel.bsp.max.export.batch.size",
                        String.valueOf(OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE))
                .containsEntry("otel.bsp.schedule.delay",
                        String.valueOf(OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY.toMillis()))
                .containsEntry("otel.bsp.export.timeout",
                        String.valueOf(OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT.toMillis()));
    }

    @Test
    void supply_возвращает_все_span_limit_дефолты() {
        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider().supply();

        // then
        assertThat(defaults)
                .containsEntry("otel.span.attribute.count.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_COUNT_LIMIT))
                .containsEntry("otel.span.attribute.value.length.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT))
                .containsEntry("otel.span.event.count.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_EVENT_COUNT_LIMIT))
                .containsEntry("otel.span.link.count.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_LINK_COUNT_LIMIT))
                .containsEntry("otel.attribute.count.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_ATTRIBUTE_COUNT_LIMIT));
    }

    @Test
    void supply_не_содержит_пустых_значений() {
        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider().supply();

        // then: все значения непустые.
        assertThat(defaults).isNotEmpty();
        defaults.forEach((key, value) ->
                assertThat(value).as("значение для ключа '%s' не должно быть пустым", key).isNotBlank());
    }

    @Test
    void supply_содержит_drop_oldest_как_default_overflow_policy() {
        // Требование §2.5 Traces Requests.txt: default policy = DROP_OLDEST.
        Map<String, String> defaults = new PlatformTracingDefaultsProvider().supply();

        assertThat(defaults)
                .containsEntry(ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY,
                        QueueOverflowPolicy.DROP_OLDEST.value());
    }

    @Test
    void supply_env_var_переопределяет_default_overflow_policy() {
        // Env-var имеет приоритет над платформенным default, но уступает System property.
        PlatformTracingDefaultsProvider provider =
                new PlatformTracingDefaultsProvider(name ->
                        ExtensionEnvironmentVariables.QUEUE_OVERFLOW_POLICY.equals(name) ? "UPSTREAM" : null);

        Map<String, String> defaults = provider.supply();

        assertThat(defaults)
                .containsEntry(ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY, "UPSTREAM");
    }

    @Test
    void supply_возвращает_независимую_копию_на_каждый_вызов() {
        // given
        PlatformTracingDefaultsProvider provider = new PlatformTracingDefaultsProvider();

        // when: модификация результата первого вызова не должна повлиять на следующий вызов.
        Map<String, String> first = provider.supply();
        first.put("otel.injected", "yes");
        Map<String, String> second = provider.supply();

        // then
        assertThat(second).doesNotContainKey("otel.injected");
    }
}
