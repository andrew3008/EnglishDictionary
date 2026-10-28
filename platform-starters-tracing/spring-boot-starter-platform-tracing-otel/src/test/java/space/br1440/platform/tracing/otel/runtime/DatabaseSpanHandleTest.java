package space.br1440.platform.tracing.otel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.DatabaseSpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class DatabaseSpanHandleTest {

    private static final String SPAN_NAME = "SELECT orders";
    private static final String EXCEPTION_TYPE = IllegalStateException.class.getName();

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultTraceOperations tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void statusCodeThenRecordException_setsCodeOnBothAttributes() {
        // given
        IllegalStateException failure = new IllegalStateException("SELECT * FROM users WHERE ssn='123'");
        DatabaseSpanHandle handle = startDatabase();

        // when
        handle.responseStatusCode("23505");
        handle.recordException(failure);
        handle.close();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isEqualTo("23505");
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("23505");
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isNotEqualTo(failure.getMessage());
        assertThat(span.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEmpty();
        EventData event = span.getEvents().getFirst();
        assertThat(span.getEvents()).hasSize(1);
        assertThat(event.getName()).isEqualTo("exception");
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_TYPE)).isEqualTo(EXCEPTION_TYPE);
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isNull();
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    @Test
    void recordExceptionWithoutStatusCode_usesExceptionClassAsErrorType() {
        // given
        DatabaseSpanHandle handle = startDatabase();

        // when
        handle.recordException(new IllegalStateException("connection refused"));
        handle.close();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo(EXCEPTION_TYPE);
        assertThat(span.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isNull();
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo(EXCEPTION_TYPE);
    }

    @Test
    void statusCodeWithoutException_writesStatusCodeAndClosesAsSuccess() {
        // given
        DatabaseSpanHandle handle = startDatabase();

        // when
        handle.responseStatusCode("00000");
        handle.close();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isEqualTo("00000");
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isNull();
        assertThat(span.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo(SpanResult.SUCCESS.value());
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(span.getEvents()).isEmpty();
    }

    @Test
    void blankAndOverlongStatusCode_areIgnored() {
        // given
        DatabaseSpanHandle handle = startDatabase();

        // when
        handle.responseStatusCode(null);
        handle.responseStatusCode("");
        handle.responseStatusCode("   ");
        handle.responseStatusCode("23 505");
        handle.responseStatusCode("A".repeat(65));
        handle.recordException(new IllegalStateException("connection refused"));
        handle.close();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isNull();
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo(EXCEPTION_TYPE);
    }

    @Test
    void maximumLengthAndSymbolicStatusCodes_areAdmitted() {
        // given
        String code = "A".repeat(64);
        DatabaseSpanHandle handle = startDatabase();

        // when
        handle.responseStatusCode(code);
        handle.recordException(new IllegalStateException("duplicate key"));
        handle.close();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isEqualTo(code);
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo(code);
    }

    @Test
    void symbolicStatusCodes_matchErrorType() {
        // given
        DatabaseSpanHandle handle = startDatabase();

        // when
        handle.responseStatusCode("ORA-17002");
        handle.responseStatusCode("42000/1071");
        handle.recordException(new IllegalStateException("duplicate key"));
        handle.close();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isEqualTo("42000/1071");
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("42000/1071");
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo(EXCEPTION_TYPE);
    }

    @Test
    void rejectedCode_keepsPreviouslyAdmittedCode() {
        // given
        DatabaseSpanHandle handle = startDatabase();
        handle.responseStatusCode("23505");

        // when
        handle.responseStatusCode(" ");
        handle.responseStatusCode("23 505");
        handle.responseStatusCode("A".repeat(65));
        handle.recordException(new IllegalStateException("duplicate key"));
        handle.close();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isEqualTo("23505");
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("23505");
    }

    @Test
    void statusCodeAfterRecordException_updatesErrorTypeWithoutSecondEvent() {
        // given
        IllegalStateException failure = new IllegalStateException("duplicate key");
        DatabaseSpanHandle handle = startDatabase();
        handle.recordException(failure);

        // when
        handle.responseStatusCode("23505");
        handle.close();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("23505");
        assertThat(span.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isEqualTo("23505");
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo(EXCEPTION_TYPE);
    }

    @Test
    void repeatedRecordException_sameIdentity_recordsOneEvent() {
        // given
        IllegalStateException failure = new IllegalStateException("duplicate key");
        DatabaseSpanHandle handle = startDatabase();
        handle.responseStatusCode("23505");

        // when
        handle.recordException(failure);
        handle.recordException(failure);
        handle.close();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("23505");
    }

    @Test
    void statusCodeWhileChildIsCurrent_staysOnDatabaseSpan() {
        // given
        IllegalStateException failure = new IllegalStateException("duplicate key");
        DatabaseSpanHandle parent = startDatabase();

        // when
        try (SpanHandle child = tracing.spans().operation("child").start()) {
            parent.responseStatusCode("23505");
            parent.recordException(failure);
        }
        parent.close();

        // then
        SpanData databaseSpan = finishedSpan();
        SpanData childSpan = exporter.getFinishedSpanItems().stream()
                .filter(span -> "child".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(databaseSpan.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("23505");
        assertThat(databaseSpan.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isEqualTo("23505");
        assertThat(childSpan.getAttributes().get(SemconvKeys.ERROR_TYPE)).isNull();
        assertThat(childSpan.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isNull();
    }

    @Test
    void statusCodeAfterClose_doesNotReplaceExportedCode() {
        // given
        DatabaseSpanHandle handle = startDatabase();
        handle.responseStatusCode("23505");
        handle.close();

        // when
        assertThatCode(() -> handle.responseStatusCode("23503")).doesNotThrowAnyException();

        // then
        SpanData span = finishedSpan();
        assertThat(span.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isEqualTo("23505");
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isNull();
    }

    @Test
    void invalidStatusCode_doesNotThrow() {
        // given
        DatabaseSpanHandle handle = startDatabase();

        // when
        assertThatCode(() -> handle.responseStatusCode("кириллица")).doesNotThrowAnyException();
        handle.close();

        // then
        assertThat(finishedSpan().getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isNull();
    }

    @Test
    void noOpDatabaseHandle_acceptsStatusCode() {
        // given
        SpanHandle database = NoOpTracingRuntime.noop().startSpan(databaseSpec());
        SpanHandle internal = NoOpTracingRuntime.noop().startSpan(
                SpanSpec.builder("op").category(SpanCategory.INTERNAL).build());

        // when / then
        assertThat(database).isSameAs(NoOpDatabaseSpanHandle.INSTANCE);
        assertThat(internal).isSameAs(NoOpSpanHandle.INSTANCE);
        assertThatCode(() -> ((DatabaseSpanHandle) database).responseStatusCode("23505"))
                .doesNotThrowAnyException();
        database.close();
    }

    private DatabaseSpanHandle startDatabase() {
        return tracing.spans().transport().database()
                .system("postgresql")
                .operation("SELECT")
                .collection("orders")
                .start();
    }

    private SpanData finishedSpan() {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> SPAN_NAME.equals(span.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static SpanSpec databaseSpec() {
        return SpanSpec.builder("orders").category(SpanCategory.DATABASE).build();
    }
}
