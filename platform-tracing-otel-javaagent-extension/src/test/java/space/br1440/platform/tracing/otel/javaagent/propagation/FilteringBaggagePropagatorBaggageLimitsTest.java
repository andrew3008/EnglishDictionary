package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression на security fix OTel SDK 1.62.0 (GHSA-rcgg-9c38-7xpx, PR #8378):
 * лимиты W3C baggage на входящем пути.
 *
 * <p><b>Контекст.</b> {@link FilteringBaggagePropagator} делегирует {@code extract()} штатному
 * {@link W3CBaggagePropagator} (фильтрация allowlist'ом применяется только на {@code inject()}).
 * Поэтому лимиты из fix #8378 — {@code MAX_BAGGAGE_ENTRIES = 64} и {@code MAX_BAGGAGE_BYTES = 8192} —
 * наследуются автоматически через делегирование. Этот тест фиксирует факт наследования, чтобы
 * будущий рефакторинг {@code extract()} не вырезал защиту от unbounded-парсинга недоверенного
 * заголовка.</p>
 */
class FilteringBaggagePropagatorBaggageLimitsTest {

    /** Лимиты из спецификации W3C baggage, применяемые штатным propagator'ом (см. fix #8378). */
    private static final int MAX_BAGGAGE_ENTRIES = 64;
    private static final int MAX_BAGGAGE_BYTES = 8192;

    private static final Set<String> ALLOWLIST = Set.of("traffic_source", "tenant_class");
    private static final List<String> DENY = List.of("password", "secret", "token");

    /** Getter поверх Map: возвращает заголовок {@code baggage} как единственное значение. */
    private static final TextMapGetter<Map<String, String>> MAP_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier == null ? null : carrier.get(key);
                }
            };

    private static FilteringBaggagePropagator newPropagator() {
        return new FilteringBaggagePropagator(
                W3CBaggagePropagator.getInstance(), ALLOWLIST, DENY);
    }

    @Test
    @DisplayName("extract: входящий заголовок свыше 64 записей урезается до лимита W3C (наследуется от stock)")
    void extract_cappedAtMaxEntries() {
        // given: заголовок с числом записей заметно больше лимита
        int oversized = MAX_BAGGAGE_ENTRIES + 36; // 100 записей
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < oversized; i++) {
            if (i > 0) {
                header.append(',');
            }
            header.append("k").append(i).append("=v").append(i);
        }
        Map<String, String> carrier = new HashMap<>();
        carrier.put("baggage", header.toString());

        // when: extract делегируется штатному W3CBaggagePropagator
        Context extracted = newPropagator().extract(Context.root(), carrier, MAP_GETTER);

        // then: число записей ограничено лимитом — защита от unbounded-парсинга активна
        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.size())
                .as("extract должен наследовать лимит MAX_BAGGAGE_ENTRIES от stock W3CBaggagePropagator")
                .isEqualTo(MAX_BAGGAGE_ENTRIES);
    }

    @Test
    @DisplayName("extract: входящий заголовок свыше 8192 байт целиком отбрасывается (защита от DoS)")
    void extract_oversizedHeaderDropped() {
        // given: единственный заголовок, превышающий байтовый лимит
        String hugeValue = "a".repeat(MAX_BAGGAGE_BYTES + 1000);
        Map<String, String> carrier = new HashMap<>();
        carrier.put("baggage", "k=" + hugeValue);

        // when
        Context extracted = newPropagator().extract(Context.root(), carrier, MAP_GETTER);

        // then: заголовок сверх байтового лимита не парсится — baggage пуст
        assertThat(Baggage.fromContext(extracted).isEmpty())
                .as("заголовок свыше MAX_BAGGAGE_BYTES должен отбрасываться целиком")
                .isTrue();
    }

    @Test
    @DisplayName("inject: allowlist-фильтрация исходящего baggage не регрессировала")
    void inject_allowlistFilteringStillWorks() {
        // given: контекст с deny-ключом (password) и разрешённым ключом
        Context ctx = Context.root().with(Baggage.builder()
                .put("password", "secret123")
                .put("traffic_source", "qa")
                .build());
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then: deny-ключ не утёк, разрешённый ключ присутствует
        String baggage = carrier.get("baggage");
        assertThat(baggage)
                .doesNotContain("password")
                .doesNotContain("secret123")
                .contains("traffic_source=qa");
    }
}
