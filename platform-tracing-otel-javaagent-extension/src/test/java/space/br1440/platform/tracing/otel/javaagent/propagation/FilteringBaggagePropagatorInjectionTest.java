package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntryMetadata;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * Slice 1 (characterization, проходит на дорефакторинговом коде) и Slice 2 (RED/GREEN
 * conformance для исправления W3C outbound baggage encoding) для {@link FilteringBaggagePropagator}.
 *
 * <p>Таксономия обязательна: методы с суффиксом {@code Characterization} фиксируют поведение,
 * не зависящее от корректности wire-encoding, и обязаны проходить как до, так и после Slice 2.
 * Методы с суффиксом {@code Conformance} проверяют исправленный W3C-корректный encoding и до
 * Slice 2 production edit ожидаемо RED.
 */
@DisplayName("FilteringBaggagePropagator: inject")
class FilteringBaggagePropagatorInjectionTest {

    private static final Set<String> ALLOWLIST = Set.of("traffic_source", "tenant_class");
    private static final List<String> DENY = List.of("password", "secret", "token");

    private static FilteringBaggagePropagator newPropagator() {
        return new FilteringBaggagePropagator(W3CBaggagePropagator.getInstance(), ALLOWLIST, DENY);
    }

    @Test
    @DisplayName("characterization: полностью отфильтрованный baggage не вызывает setter.set ни разу")
    void fullyFilteredBaggage_neverCallsSetter_characterization() {
        // given: baggage содержит только deny-ключи, ни один allowlisted key не проходит фильтр
        Context ctx = Context.root().with(Baggage.builder()
                .put("password", "secret123")
                .put("token", "jwt-abc")
                .build());
        AtomicInteger setterCalls = new AtomicInteger();
        TextMapSetter<Map<String, String>> countingSetter = (carrier, key, value) -> {
            setterCalls.incrementAndGet();
            carrier.put(key, value);
        };
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, countingSetter);

        // then: единственная точка цепочки, где no-partial-state гарантирован структурой кода
        assertThat(setterCalls.get()).isZero();
        assertThat(carrier).isEmpty();
    }

    @Test
    @DisplayName("characterization: пустой (без entries) baggage не вызывает setter.set ни разу")
    void emptyBaggage_neverCallsSetter_characterization() {
        Context ctx = Context.root();
        AtomicInteger setterCalls = new AtomicInteger();
        TextMapSetter<Map<String, String>> countingSetter = (carrier, key, value) -> setterCalls.incrementAndGet();
        Map<String, String> carrier = new HashMap<>();

        newPropagator().inject(ctx, carrier, countingSetter);

        assertThat(setterCalls.get()).isZero();
        assertThat(carrier).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Slice 2 (conformance, §6.1 плана): исправление P1 — ручная сериализация заменена
    // делегированием stock W3CBaggagePropagator. До production edit ожидается RED
    // (текущая реализация кодирует пробел как '+', а не '%20').
    // ------------------------------------------------------------------------------------------

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    @Test
    @DisplayName("conformance: ASCII space кодируется как %20 (не '+') и проходит stock round-trip")
    void spaceEncodedAsPercent20_stockRoundTrip_conformance() {
        // given: значение с пробелом ("source value: DF 28" из §6.1)
        Context ctx = Context.root().with(Baggage.builder().put("tenant_class", "DF 28").build());
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then: заголовок использует W3C/OTel-корректный %20, а не '+'
        String header = carrier.get("baggage");
        assertThat(header).contains("tenant_class=DF%2028");
        assertThat(header).doesNotContain("DF+28");

        // and: stock extraction возвращает исходное значение без изменения
        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(), carrier, MAP_GETTER);
        assertThat(Baggage.fromContext(extracted).getEntryValue("tenant_class")).isEqualTo("DF 28");
    }

    @Test
    @DisplayName("conformance: UTF-8 Unicode проходит stock inject/extract round-trip без изменения значения")
    void unicodeValue_stockRoundTrip_conformance() {
        String original = "тест-значение-Ω";
        Context ctx = Context.root().with(Baggage.builder().put("tenant_class", original).build());
        Map<String, String> carrier = new HashMap<>();

        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(), carrier, MAP_GETTER);
        assertThat(Baggage.fromContext(extracted).getEntryValue("tenant_class")).isEqualTo(original);
    }

    @Test
    @DisplayName("conformance: denied/non-allowlisted ключи отсутствуют в заголовке целиком")
    void deniedKeys_absentFromHeader_conformance() {
        Context ctx = Context.root().with(Baggage.builder()
                .put("password", "leak")
                .put("tenant_class", "gold")
                .put("not_allowlisted", "x")
                .build());
        Map<String, String> carrier = new HashMap<>();

        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        String header = carrier.get("baggage");
        assertThat(header).doesNotContain("password").doesNotContain("leak").doesNotContain("not_allowlisted");
        assertThat(header).contains("tenant_class=gold");
    }

    @Test
    @DisplayName("conformance: BaggageEntryMetadata сохраняется и кодируется штатным delegate")
    void metadataPreserved_encodedByStockDelegate_conformance() {
        Context ctx = Context.root().with(Baggage.builder()
                .put("tenant_class", "gold", BaggageEntryMetadata.create("verified=true"))
                .build());
        Map<String, String> carrier = new HashMap<>();

        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(), carrier, MAP_GETTER);
        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.getEntryValue("tenant_class")).isEqualTo("gold");
        assertThat(baggage.asMap().get("tenant_class").getMetadata().getValue()).isEqualTo("verified=true");
    }

    @Test
    @DisplayName("conformance: свыше 64 entries и заголовок свыше 8192 байт ограничены stock OTel contract")
    void overLimitEntries_boundedByStockContract_conformance() {
        // given: allowlist и baggage со 100 разрешёнными записями (заведомо больше лимита 64)
        java.util.Set<String> allowedKeys = new java.util.HashSet<>();
        BaggageBuilder builder = Baggage.builder();
        for (int i = 0; i < 100; i++) {
            String key = "k" + i;
            allowedKeys.add(key);
            builder.put(key, "v" + i);
        }
        FilteringBaggagePropagator propagator =
                new FilteringBaggagePropagator(W3CBaggagePropagator.getInstance(), allowedKeys, DENY);
        Context ctx = Context.root().with(builder.build());
        Map<String, String> carrier = new HashMap<>();

        // when
        propagator.inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then: итоговый заголовок не превышает stock byte-лимит, а извлечённый baggage —
        // stock entry-лимит (проверяем upper bound, а не конкретный набор из 64 ключей — порядок
        // immutable Baggage не гарантирован)
        String header = carrier.get("baggage");
        assertThat(header).isNotNull();
        assertThat(header.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);

        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(), carrier, MAP_GETTER);
        assertThat(Baggage.fromContext(extracted).size()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("conformance: исходный Context/Baggage не мутируются inject-ом")
    void originalContextAndBaggage_notMutated_conformance() {
        Baggage original = Baggage.builder()
                .put("password", "leak")
                .put("tenant_class", "gold")
                .build();
        Context ctx = Context.root().with(original);
        Map<String, String> carrier = new HashMap<>();

        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        Baggage stillOriginal = Baggage.fromContext(ctx);
        assertThat(stillOriginal.getEntryValue("password")).isEqualTo("leak");
        assertThat(stillOriginal.size()).isEqualTo(2);
    }
}
