package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageKeyPolicy;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

class PlatformBaggagePropagatorBaggageLimitsTest {

    private static final int MAX_BAGGAGE_ENTRIES = 64;
    private static final int MAX_BAGGAGE_BYTES = 8192;

    private static final Set<String> ALLOWLIST = Set.of("traffic_source", "tenant_class");
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

    private static PlatformBaggagePropagator newPropagator() {
        return new PlatformBaggagePropagator(
                W3CBaggagePropagator.getInstance(),
                new BaggageKeyPolicy(ALLOWLIST, List.of())
        );
    }

    @Test
    @DisplayName("extract: входящий заголовок свыше 64 записей урезается до лимита W3C (наследуется от stock)")
    void extract_cappedAtMaxEntries() {
        // given
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

        // when
        Context extracted = newPropagator().extract(Context.root(), carrier, MAP_GETTER);

        // then
        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.size())
                .as("extract должен наследовать лимит MAX_BAGGAGE_ENTRIES от stock W3CBaggagePropagator")
                .isEqualTo(MAX_BAGGAGE_ENTRIES);
    }

    @Test
    @DisplayName("extract: входящий заголовок свыше 8192 байт целиком отбрасывается (защита от DoS)")
    void extract_oversizedHeaderDropped() {
        // given
        String hugeValue = "a".repeat(MAX_BAGGAGE_BYTES + 1000);
        Map<String, String> carrier = new HashMap<>();
        carrier.put("baggage", "k=" + hugeValue);

        // when
        Context extracted = newPropagator().extract(Context.root(), carrier, MAP_GETTER);

        // then
        assertThat(Baggage.fromContext(extracted).isEmpty())
                .as("заголовок свыше MAX_BAGGAGE_BYTES должен отбрасываться целиком")
                .isTrue();
    }

    @Test
    @DisplayName("inject: allowlist-фильтрация исходящего baggage не регрессировала")
    void inject_allowlistFilteringStillWorks() {
        // given
        Context ctx = Context.root().with(Baggage.builder()
                .put("password", "secret123")
                .put("traffic_source", "qa")
                .build());
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then
        String baggage = carrier.get("baggage");
        assertThat(baggage)
                .doesNotContain("password")
                .doesNotContain("secret123")
                .contains("traffic_source=qa");
    }

    @Test
    @DisplayName("extract: empty W3C baggage value is retained on OTel 1.64")
    void extractRetainsEmptyBaggageValue() {
        // given
        Map<String, String> carrier = Map.of("baggage", "empty=,traffic_source=qa");

        // when
        Baggage baggage = Baggage.fromContext(
                newPropagator().extract(Context.root(), carrier, MAP_GETTER));

        // then
        assertThat(baggage.getEntryValue("empty")).isEmpty();
        assertThat(baggage.getEntryValue("traffic_source")).isEqualTo("qa");
    }

    @Test
    @DisplayName("extract: malformed percent encoding is discarded without damaging a valid neighbour")
    void extractDiscardsMalformedPercentEncodingAndKeepsValidNeighbour() {
        // given
        Map<String, String> carrier = Map.of("baggage", "broken=%ZZ,traffic_source=qa");

        // when
        Baggage baggage = Baggage.fromContext(
                newPropagator().extract(Context.root(), carrier, MAP_GETTER));

        // then
        assertThat(baggage.getEntryValue("broken")).isNull();
        assertThat(baggage.getEntryValue("traffic_source")).isEqualTo("qa");
    }
}
