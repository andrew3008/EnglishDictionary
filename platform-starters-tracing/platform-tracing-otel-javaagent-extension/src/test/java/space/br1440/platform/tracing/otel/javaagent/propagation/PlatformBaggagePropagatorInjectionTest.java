package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageKeyPolicy;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntryMetadata;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

@DisplayName("PlatformBaggagePropagator: исходящая фильтрация и сериализация W3C")
class PlatformBaggagePropagatorInjectionTest {

    private static final Set<String> ALLOWLIST = Set.of("traffic_source", "tenant_class");
    private static final List<Pattern> DENY = List.of("password", "secret", "token").stream()
            .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
            .toList();

    private static PlatformBaggagePropagator newPropagator() {
        return new PlatformBaggagePropagator(
                W3CBaggagePropagator.getInstance(), new BaggageKeyPolicy(ALLOWLIST, DENY));
    }

    @Test
    @DisplayName("characterization: полностью отфильтрованный baggage не вызывает setter.set ни разу")
    void fullyFilteredBaggage_neverCallsSetter_characterization() {
        // given
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

        // then
        assertThat(setterCalls.get()).isZero();
        assertThat(carrier).isEmpty();
    }

    @Test
    @DisplayName("characterization: пустой (без entries) baggage не вызывает setter.set ни разу")
    void emptyBaggage_neverCallsSetter_characterization() {
        // given
        Context ctx = Context.root();
        AtomicInteger setterCalls = new AtomicInteger();
        TextMapSetter<Map<String, String>> countingSetter = (carrier, key, value) -> setterCalls.incrementAndGet();
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, countingSetter);

        // then
        assertThat(setterCalls.get()).isZero();
        assertThat(carrier).isEmpty();
    }

    @Test
    @DisplayName("extract не применяет исходящие allowlist и deny-паттерны")
    void extract_doesNotApplyOutboundPolicy() {
        // given
        Map<String, String> carrier = new HashMap<>();
        Context outboundCtx = Context.root().with(Baggage.builder()
                .put("password", "leak")
                .put("not_allowlisted", "x")
                .put("tenant_class", "gold")
                .build());
        W3CBaggagePropagator.getInstance().inject(outboundCtx, carrier, (c, k, v) -> c.put(k, v));

        // when
        Context extracted = newPropagator().extract(Context.root(), carrier, MAP_GETTER);

        // then
        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.getEntryValue("password"))
                .as("extract не вычищает deny-ключи — фильтрация только на inject")
                .isEqualTo("leak");
        assertThat(baggage.getEntryValue("not_allowlisted"))
                .as("extract не проверяет allowlist — фильтрация только на inject")
                .isEqualTo("x");
        assertThat(baggage.getEntryValue("tenant_class")).isEqualTo("gold");
    }

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
        // given
        Context ctx = Context.root().with(Baggage.builder().put("tenant_class", "DF 28").build());
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then
        String header = carrier.get("baggage");
        assertThat(header).contains("tenant_class=DF%2028");
        assertThat(header).doesNotContain("DF+28");

        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(), carrier, MAP_GETTER);
        assertThat(Baggage.fromContext(extracted).getEntryValue("tenant_class")).isEqualTo("DF 28");
    }

    @Test
    @DisplayName("conformance: UTF-8 Unicode проходит stock inject/extract round-trip без изменения значения")
    void unicodeValue_stockRoundTrip_conformance() {
        // given
        String original = "тест-значение-Ω";
        Context ctx = Context.root().with(Baggage.builder().put("tenant_class", original).build());
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));
        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(), carrier, MAP_GETTER);

        // then
        assertThat(Baggage.fromContext(extracted).getEntryValue("tenant_class")).isEqualTo(original);
    }

    @Test
    @DisplayName("conformance: denied/non-allowlisted ключи отсутствуют в заголовке целиком")
    void deniedKeys_absentFromHeader_conformance() {
        // given
        Context ctx = Context.root().with(Baggage.builder()
                .put("password", "leak")
                .put("tenant_class", "gold")
                .put("not_allowlisted", "x")
                .build());
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then
        String header = carrier.get("baggage");
        assertThat(header).doesNotContain("password").doesNotContain("leak").doesNotContain("not_allowlisted");
        assertThat(header).contains("tenant_class=gold");
    }

    @Test
    @DisplayName("conformance: BaggageEntryMetadata сохраняется и кодируется штатным delegate")
    void metadataPreserved_encodedByStockDelegate_conformance() {
        // given
        Context ctx = Context.root().with(Baggage.builder()
                .put("tenant_class", "gold", BaggageEntryMetadata.create("verified=true"))
                .put("not_allowlisted", "removed")
                .build());
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));
        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(), carrier, MAP_GETTER);

        // then
        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.getEntryValue("tenant_class")).isEqualTo("gold");
        assertThat(baggage.asMap().get("tenant_class").getMetadata().getValue()).isEqualTo("verified=true");
    }

    @Test
    @DisplayName("conformance: свыше 64 entries и заголовок свыше 8192 байт ограничены stock OTel contract")
    void overLimitEntries_boundedByStockContract_conformance() {
        // given
        Set<String> allowedKeys = new HashSet<>();
        BaggageBuilder builder = Baggage.builder();
        for (int i = 0; i < 100; i++) {
            String key = "k" + i;
            allowedKeys.add(key);
            builder.put(key, "v" + i);
        }
        PlatformBaggagePropagator propagator = new PlatformBaggagePropagator(
                W3CBaggagePropagator.getInstance(), new BaggageKeyPolicy(allowedKeys, DENY));
        Context ctx = Context.root().with(builder.build());
        Map<String, String> carrier = new HashMap<>();

        // when
        propagator.inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then
        String header = carrier.get("baggage");
        assertThat(header).isNotNull();
        assertThat(header.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);

        Context extracted = W3CBaggagePropagator.getInstance().extract(Context.root(), carrier, MAP_GETTER);
        assertThat(Baggage.fromContext(extracted).size()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("conformance: исходный Context/Baggage не мутируются inject-ом")
    void originalContextAndBaggage_notMutated_conformance() {
        // given
        Baggage original = Baggage.builder()
                .put("password", "leak")
                .put("tenant_class", "gold")
                .build();
        Context ctx = Context.root().with(original);
        Map<String, String> carrier = new HashMap<>();

        // when
        newPropagator().inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then
        Baggage stillOriginal = Baggage.fromContext(ctx);
        assertThat(stillOriginal.getEntryValue("password")).isEqualTo("leak");
        assertThat(stillOriginal.size()).isEqualTo(2);
    }
}
