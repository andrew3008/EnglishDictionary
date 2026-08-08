package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntryMetadata;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Decision-oriented JMH matrix для outbound/inbound {@code baggage} propagation (propagation
 * cleanup Slice 1/2, §5.5 плана). Измеряет итоговый final baggage {@link TextMapPropagator},
 * полученный через реальный {@link AutoConfiguredOpenTelemetrySdk} (composition/customizer
 * boundary), а не прямое конструирование package-private implementation-типов
 * (см. G-PROP-6: benchmark не зависит от {@code FilteringBaggagePropagator}/{@code SafeTextMapPropagator}).
 * <p>
 * Каждый {@link #scenario} — отдельный JMH trial с собственным {@code @Setup(Level.Trial)}: SDK,
 * allowlist-конфигурация и подготовленный {@code Baggage}-контент зависят от сценария.
 * <p>
 * Carrier lifecycle: inject получает новый carrier {@link Map} на каждый invocation (не
 * переиспользуемая "тёплая" HashMap), {@link Blackhole#consume(Object)} потребляет именно
 * мутированный carrier — иначе {@code gc.alloc.rate.norm} несравним между before/after. Extract
 * читает предзаполненный carrier из состояния, подготовленный один раз в {@code @Setup(Level.Trial)}
 * реальным inject через тот же final propagator.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BaggagePropagationBenchmark {

    private static final String PROP_BAGGAGE_ENABLED =
            "platform.tracing.javaagent.propagation.baggage.enabled";
    private static final String PROP_BAGGAGE_ALLOWED_KEYS =
            "platform.tracing.javaagent.propagation.baggage.allowed-keys";

    private static final TextMapSetter<Map<String, String>> SETTER =
            (carrier, key, value) -> carrier.put(key, value);
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    /**
     * Decision-oriented matrix (§5.5 плана): count-only набор заменён на кейсы, значимые именно
     * для решения о correctness/performance trade-off W3C outbound baggage fix.
     */
    @Param({"empty", "oneAllowed", "mixedAllowDeny", "realisticSmall", "metadataAll", "nearLimit", "disabled"})
    public String scenario;

    private AutoConfiguredOpenTelemetrySdk sdk;
    private TextMapPropagator finalPropagator;
    private Context baseContext;
    private Map<String, String> extractCarrier;

    @Setup(Level.Trial)
    public void setUp() {
        boolean baggageEnabled = !"disabled".equals(scenario);
        java.util.List<String> allowedKeys = allowedKeysFor(scenario);

        Map<String, String> properties = new HashMap<>();
        properties.put("otel.propagators", "tracecontext,baggage");
        properties.put("otel.traces.exporter", "none");
        properties.put("otel.metrics.exporter", "none");
        properties.put("otel.logs.exporter", "none");
        properties.put(PROP_BAGGAGE_ENABLED, String.valueOf(baggageEnabled));
        properties.put(PROP_BAGGAGE_ALLOWED_KEYS, String.join(",", allowedKeys));

        sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .disableShutdownHook()
                .addPropertiesSupplier(() -> properties)
                .build();
        finalPropagator = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

        baseContext = Context.root().with(baggageFor(scenario));

        // extract carrier подготавливается один раз через реальный inject тем же final propagator —
        // это исключает дублирование production serializer в benchmark-коде.
        Map<String, String> prepared = new HashMap<>();
        finalPropagator.inject(baseContext, prepared, SETTER);
        extractCarrier = Map.copyOf(prepared);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        sdk.getOpenTelemetrySdk().close();
    }

    @Benchmark
    public void inject(Blackhole bh) {
        Map<String, String> carrier = new HashMap<>();
        finalPropagator.inject(baseContext, carrier, SETTER);
        bh.consume(carrier);
    }

    @Benchmark
    public void extract(Blackhole bh) {
        bh.consume(finalPropagator.extract(Context.root(), extractCarrier, GETTER));
    }

    private static java.util.List<String> allowedKeysFor(String scenario) {
        return switch (scenario) {
            case "empty" -> java.util.List.of("tenant_class");
            case "oneAllowed" -> java.util.List.of("tenant_class");
            case "mixedAllowDeny" -> java.util.List.of("allowed_0", "allowed_1", "allowed_2", "allowed_3");
            case "realisticSmall", "disabled" -> java.util.List.of(
                    "tenant_class", "traffic_source", "platform.correlation.id",
                    "request_priority", "feature_flag");
            case "metadataAll" -> java.util.List.of(
                    "meta_0", "meta_1", "meta_2", "meta_3", "meta_4", "meta_5", "meta_6", "meta_7");
            case "nearLimit" -> nearLimitKeys();
            default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
        };
    }

    private static Baggage baggageFor(String scenario) {
        BaggageBuilder builder = Baggage.builder();
        switch (scenario) {
            case "empty" -> {
                // намеренно ни одной entry — baseline overhead Safe+Filtering с нулевым payload
            }
            case "oneAllowed" -> builder.put("tenant_class", "gold");
            case "mixedAllowDeny" -> {
                // 8 entries, 50% denied: 4 в allowlist, 4 отсутствуют в allowlist
                for (int i = 0; i < 4; i++) {
                    builder.put("allowed_" + i, "value_" + i);
                }
                for (int i = 0; i < 4; i++) {
                    builder.put("denied_" + i, "value_" + i);
                }
            }
            case "realisticSmall", "disabled" -> {
                builder.put("tenant_class", "gold");
                builder.put("traffic_source", "web");
                builder.put("platform.correlation.id", "wf-42", BaggageEntryMetadata.create("verified=false"));
                builder.put("request_priority", "high", BaggageEntryMetadata.create("source=header"));
                builder.put("feature_flag", "on");
            }
            case "metadataAll" -> {
                for (int i = 0; i < 8; i++) {
                    builder.put("meta_" + i, "value_" + i, BaggageEntryMetadata.create("m=" + i));
                }
            }
            case "nearLimit" -> {
                for (String key : nearLimitKeys()) {
                    builder.put(key, "v");
                }
            }
            default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
        return builder.build();
    }

    /** Ровно 64 ключа — все обязаны присутствовать в allowlist (см. §5.5 плана: иначе измеряется
     * ранняя фильтрация до нескольких allowed-ключей, а не 64-entry stock boundary). */
    private static java.util.List<String> nearLimitKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>(64);
        for (int i = 0; i < 64; i++) {
            keys.add("k" + i);
        }
        return keys;
    }
}
