package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.exporter.ExporterTestSpans;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.SpanSizeLowerBound.Verdict;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;
import io.opentelemetry.exporter.internal.otlp.traces.LowAllocationTraceRequestMarshaler;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

class SpanSizeLowerBoundTest {

    private static final int LIMIT = Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES);
    private static final long MAX_BYTES_PER_UNIT = 64;
    private static final long FIXED_OVERHEAD = 1024;
    private static final SpanContext LINK_TARGET = SpanContext.create(
            "0123456789abcdef0123456789abcdef",
            "0123456789abcdef",
            TraceFlags.getSampled(),
            TraceState.getDefault()
    );

    @ParameterizedTest(name = "{0}")
    @MethodSource("variableSurfaces")
    void countsEveryVariableSurfaceWithoutRejectingAnExactWireBoundary(String name, SpanData span) throws IOException {
        // given
        int wireSize = wire(span).length;

        // when
        Verdict smallLimit = SpanSizeLowerBound.check(span, 256);
        Verdict exactWireLimit = SpanSizeLowerBound.check(span, wireSize);

        // then
        assertThat(smallLimit).isEqualTo(Verdict.EXCEEDS_LIMIT);
        assertThat(exactWireLimit).isEqualTo(Verdict.WITHIN_LIMIT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"variableSurfaces", "denseRecords"})
    void smallestAdmittingLimitBracketsTheSerializedSize(String name, SpanData span) throws IOException {
        // given
        long actual = wire(span).length;

        // when
        long bound = smallestAdmittingLimit(span);

        // then
        assertThat(bound).isLessThanOrEqualTo(actual);
        assertThat(actual).isLessThanOrEqualTo(MAX_BYTES_PER_UNIT * bound + FIXED_OVERHEAD);
    }

    @Test
    void linkDensityKeepsMarginBelowTheBytesPerUnitConstant() throws IOException {
        // given
        SpanData span = base().setLinks(Collections.nCopies(10_000, LinkData.create(LINK_TARGET))).build();
        long actual = wire(span).length;

        // when
        long bound = smallestAdmittingLimit(span);

        // then
        assertThat(actual).isLessThanOrEqualTo(40 * bound + FIXED_OVERHEAD);
    }

    @Test
    void platformRequestLimitKeepsWorstCaseSerializedSizeWithinInt() {
        assertThat(MAX_BYTES_PER_UNIT * OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES + FIXED_OVERHEAD)
                .isLessThanOrEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("valueSurfaces")
    void deepestAllowedValueIsAcceptedAndParsesWithProtobufDefaults(String surface, String container,
                                                                    Function<Value<?>, SpanData> place,
                                                                    UnaryOperator<Value<?>> wrap) throws IOException {
        // given
        SpanData span = place.apply(nest(SpanSizeLowerBound.MAX_VALUE_DEPTH, wrap));
        byte[] wire = wire(span);

        // when
        Verdict verdict = SpanSizeLowerBound.check(span, LIMIT);

        // then
        assertThat(verdict).as("%s/%s", surface, container).isEqualTo(Verdict.WITHIN_LIMIT);
        assertThatCode(() -> ExportTraceServiceRequest.parseFrom(wire)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("valueSurfaces")
    void valueNestedBeyondTheLimitIsRejectedAsTooDeep(String surface, String container,
                                                      Function<Value<?>, SpanData> place,
                                                      UnaryOperator<Value<?>> wrap) {
        // given
        SpanData span = place.apply(nest(SpanSizeLowerBound.MAX_VALUE_DEPTH + 1, wrap));

        // when
        Verdict verdict = SpanSizeLowerBound.check(span, LIMIT);

        // then
        assertThat(verdict).as("%s/%s", surface, container).isEqualTo(Verdict.VALUE_TOO_DEEP);
    }

    @Test
    void valueDeeperThanSdkMarshalersCanHandleIsRejectedWithoutStackOverflow() {
        // given
        SpanData span = base().setResource(Resource.create(Attributes.of(AttributeKey.valueKey("v"),
                nest(100_000, Value::of)))).build();

        // when
        Verdict verdict = SpanSizeLowerBound.check(span, LIMIT);

        // then
        assertThat(verdict).isEqualTo(Verdict.VALUE_TOO_DEEP);
    }

    @Test
    void emptySpanFitsZeroLimitAndOneCharacterDoesNot() {
        // given
        SpanData empty = base().build();
        SpanData oneCharacter = base().setName("x").build();

        // when
        Verdict emptyVerdict = SpanSizeLowerBound.check(empty, 0);
        Verdict oneCharacterVerdict = SpanSizeLowerBound.check(oneCharacter, 0);

        // then
        assertThat(emptyVerdict).isEqualTo(Verdict.WITHIN_LIMIT);
        assertThat(oneCharacterVerdict).isEqualTo(Verdict.EXCEEDS_LIMIT);
    }

    @Test
    void firstBreachInTraversalOrderWins() {
        // given
        Value<?> tooDeep = nest(SpanSizeLowerBound.MAX_VALUE_DEPTH + 1, Value::of);
        SpanData budgetFirst = base().setName("x".repeat(100))
                .setAttributes(Attributes.of(AttributeKey.valueKey("v"), tooDeep)).build();
        SpanData depthFirst = base().setAttributes(Attributes.of(AttributeKey.valueKey("v"), tooDeep))
                .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("k"), "x".repeat(100)))).build();

        // when
        Verdict budgetVerdict = SpanSizeLowerBound.check(budgetFirst, 50);
        Verdict depthVerdict = SpanSizeLowerBound.check(depthFirst, 50);

        // then
        assertThat(budgetVerdict).isEqualTo(Verdict.EXCEEDS_LIMIT);
        assertThat(depthVerdict).isEqualTo(Verdict.VALUE_TOO_DEEP);
    }

    @Test
    void traversalStopsAtTheFirstBreachAndDoesNotReadLaterSurfaces() {
        // given
        AtomicBoolean eventsRead = new AtomicBoolean();
        SpanData span = new DelegatingSpanData(base().setName("x".repeat(100)).build()) {
            @Override
            public List<EventData> getEvents() {
                eventsRead.set(true);
                return super.getEvents();
            }
        };

        // when
        Verdict verdict = SpanSizeLowerBound.check(span, 10);

        // then
        assertThat(verdict).isEqualTo(Verdict.EXCEEDS_LIMIT);
        assertThat(eventsRead).isFalse();
    }

    @Test
    void sharedValueInstanceIsChargedOncePerReference() {
        // given
        Value<?> shared = Value.of("x".repeat(100));
        SpanData span = base().setAttributes(Attributes.of(
                AttributeKey.valueKey("v"), Value.of(shared, shared))).build();

        // when
        Verdict below = SpanSizeLowerBound.check(span, 203);
        Verdict exact = SpanSizeLowerBound.check(span, 204);

        // then
        assertThat(below).isEqualTo(Verdict.EXCEEDS_LIMIT);
        assertThat(exact).isEqualTo(Verdict.WITHIN_LIMIT);
    }

    @Test
    void swallowedSizeBreachStillRejects() {
        // given
        Attributes attributes = new ExceptionSwallowingAttributes(
                Attributes.of(AttributeKey.stringKey("k"), "x".repeat(100)));
        SpanData span = new DelegatingSpanData(base().build()) {
            @Override
            public Attributes getAttributes() {
                return attributes;
            }
        };

        // when
        Verdict verdict = SpanSizeLowerBound.check(span, 10);

        // then
        assertThat(verdict).isEqualTo(Verdict.EXCEEDS_LIMIT);
    }

    @Test
    void swallowedDepthBreachKeepsFirstVerdict() {
        // given
        Attributes attributes = new ExceptionSwallowingAttributes(Attributes.of(
                AttributeKey.valueKey("v"), nest(SpanSizeLowerBound.MAX_VALUE_DEPTH + 1, Value::of)));
        SpanData span = new DelegatingSpanData(base()
                .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("k"), "x".repeat(100))))
                .build()) {
            @Override
            public Attributes getAttributes() {
                return attributes;
            }
        };

        // when
        Verdict verdict = SpanSizeLowerBound.check(span, 50);

        // then
        assertThat(verdict).isEqualTo(Verdict.VALUE_TOO_DEEP);
    }

    private static Stream<Arguments> variableSurfaces() {
        String text = "漢漢".repeat(400);
        Attributes attributes = Attributes.of(AttributeKey.stringKey("payload"), text);
        SpanContext context = SpanContext.create("1".repeat(32), "2".repeat(16), TraceFlags.getSampled(),
                TraceState.builder().put("first", "x".repeat(200)).put("second", "y".repeat(200)).build());
        Value<?> nested = Value.of(
                KeyValue.of(
                        "outer",
                        Value.of(Value.empty(),
                        Value.of(Map.of("inner", Value.of(text))),
                        Value.of(new byte[400]))
                )
        );

        return Stream.of(
                Arguments.of("span name", base().setName(text).build()),
                Arguments.of("status description", base().setStatus(StatusData.create(StatusCode.ERROR, text)).build()),
                Arguments.of("span attributes", base().setAttributes(attributes).build()),
                Arguments.of("resource attributes", base().setResource(Resource.create(attributes)).build()),
                Arguments.of("resource schema", base().setResource(Resource.create(Attributes.empty(), text)).build()),
                Arguments.of("scope name", base().setInstrumentationScopeInfo(InstrumentationScopeInfo.create(text)).build()),
                Arguments.of("scope version", base().setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("")
                        .setVersion(text).build()).build()),
                Arguments.of("scope schema", base().setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("")
                        .setSchemaUrl(text).build()).build()),
                Arguments.of("scope attributes", base().setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("")
                        .setAttributes(attributes).build()).build()),
                Arguments.of("event name", base().setEvents(List.of(EventData.create(1L, text, Attributes.empty()))).build()),
                Arguments.of("event attributes", base().setEvents(List.of(EventData.create(1L, "", attributes))).build()),
                Arguments.of("link attributes", base().setLinks(List.of(LinkData.create(SpanContext.getInvalid(), attributes))).build()),
                Arguments.of("span tracestate", base().setSpanContext(context).build()),
                Arguments.of("link tracestate", base().setLinks(List.of(LinkData.create(context))).build()),
                Arguments.of("repeated events", base().setEvents(Collections.nCopies(300,
                        EventData.create(0L, "", Attributes.empty()))).build()),
                Arguments.of("repeated links", base().setLinks(Collections.nCopies(300,
                        LinkData.create(SpanContext.getInvalid()))).build()),
                Arguments.of("string array", base().setAttributes(Attributes.of(AttributeKey.stringArrayKey("a"),
                        Collections.nCopies(300, ""))).build()),
                Arguments.of("long array", base().setAttributes(Attributes.of(AttributeKey.longArrayKey("a"),
                        Collections.nCopies(300, 0L))).build()),
                Arguments.of("double array", base().setAttributes(Attributes.of(AttributeKey.doubleArrayKey("a"),
                        Collections.nCopies(300, 0D))).build()),
                Arguments.of("boolean array", base().setAttributes(Attributes.of(AttributeKey.booleanArrayKey("a"),
                        Collections.nCopies(300, false))).build()),
                Arguments.of("nested value", base().setAttributes(Attributes.of(AttributeKey.valueKey("v"), nested)).build()),
                Arguments.of("bytes value", base().setAttributes(Attributes.of(AttributeKey.valueKey("v"), Value.of(new byte[400]))).build()),
                Arguments.of("empty values", base().setAttributes(Attributes.of(AttributeKey.valueKey("v"),
                        Value.of(Collections.nCopies(300, Value.empty())))).build())
        );
    }

    private static Stream<Arguments> denseRecords() {
        return Stream.of(
                Arguments.of("10000 links without attributes", base()
                        .setLinks(Collections.nCopies(10_000, LinkData.create(LINK_TARGET))).build()),
                Arguments.of("10000 events without attributes", base()
                        .setEvents(Collections.nCopies(10_000, EventData.create(Long.MAX_VALUE, "", Attributes.empty())))
                        .build()),
                Arguments.of("10000 long array elements", base().setAttributes(Attributes.of(
                        AttributeKey.longArrayKey("a"), Collections.nCopies(10_000, Long.MIN_VALUE))).build()),
                Arguments.of("10000 double array elements", base().setAttributes(Attributes.of(
                        AttributeKey.doubleArrayKey("a"), Collections.nCopies(10_000, 1.5D))).build()),
                Arguments.of("multibyte text", base().setName("漢".repeat(1000))
                        .setAttributes(Attributes.of(AttributeKey.stringKey("k"), "漢".repeat(1000))).build()),
                Arguments.of("32x512 tracestate", base().setSpanContext(SpanContext.create("1".repeat(32),
                        "2".repeat(16), TraceFlags.getSampled(), maxTraceState())).build())
        );
    }

    private static Stream<Arguments> valueSurfaces() {
        AttributeKey<Value<?>> key = AttributeKey.valueKey("v");
        List<Arguments> surfaces = List.of(
                Arguments.of("span", (Function<Value<?>, SpanData>) value -> base()
                        .setAttributes(Attributes.of(key, value)).build()),
                Arguments.of("resource", (Function<Value<?>, SpanData>) value -> base()
                        .setResource(Resource.create(Attributes.of(key, value))).build()),
                Arguments.of("scope", (Function<Value<?>, SpanData>) value -> base()
                        .setInstrumentationScopeInfo(InstrumentationScopeInfo.builder("")
                                .setAttributes(Attributes.of(key, value)).build()).build()),
                Arguments.of("event", (Function<Value<?>, SpanData>) value -> base()
                        .setEvents(List.of(EventData.create(1L, "", Attributes.of(key, value)))).build()),
                Arguments.of("link", (Function<Value<?>, SpanData>) value -> base()
                        .setLinks(List.of(LinkData.create(LINK_TARGET, Attributes.of(key, value)))).build()));
        List<Arguments> containers = List.of(
                Arguments.of("array", (UnaryOperator<Value<?>>) Value::of),
                Arguments.of("kvlist", (UnaryOperator<Value<?>>) inner -> Value.of(KeyValue.of("k", inner))));
        return surfaces.stream().flatMap(surface -> containers.stream().map(container -> Arguments.of(
                surface.get()[0], container.get()[0], surface.get()[1], container.get()[1])));
    }

    private static Value<?> nest(int depth, UnaryOperator<Value<?>> wrap) {
        Value<?> value = Value.of("x");
        for (int level = 0; level < depth; level++) {
            value = wrap.apply(value);
        }
        return value;
    }

    private static TraceState maxTraceState() {
        TraceStateBuilder builder = TraceState.builder();
        for (int index = 0; index < 32; index++) {
            builder.put(("k" + index + "x".repeat(256)).substring(0, 256), "v".repeat(256));
        }
        TraceState state = builder.build();
        assertThat(state.size()).isEqualTo(32);
        return state;
    }

    private static int smallestAdmittingLimit(SpanData span) {
        int low = 0;
        int high = Integer.MAX_VALUE;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (SpanSizeLowerBound.check(span, middle) == Verdict.WITHIN_LIMIT) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private static byte[] wire(SpanData span) throws IOException {
        LowAllocationTraceRequestMarshaler marshaler = new LowAllocationTraceRequestMarshaler();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        marshaler.initialize(List.of(span));
        try {
            marshaler.writeBinaryTo(wire);
        } finally {
            marshaler.reset();
        }
        return wire.toByteArray();
    }

    private static TestSpanData.Builder base() {
        return ExporterTestSpans.baseSpan("").setResource(Resource.empty())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.create(""));
    }

    private record ExceptionSwallowingAttributes(Attributes delegate) implements Attributes {

        @Override
        public <T> T get(AttributeKey<T> key) {
            return delegate.get(key);
        }

        @Override
        public void forEach(BiConsumer<? super AttributeKey<?>, ? super Object> consumer) {
            try {
                delegate.forEach(consumer);
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public Map<AttributeKey<?>, Object> asMap() {
            return delegate.asMap();
        }

        @Override
        public AttributesBuilder toBuilder() {
            return delegate.toBuilder();
        }
    }
}
