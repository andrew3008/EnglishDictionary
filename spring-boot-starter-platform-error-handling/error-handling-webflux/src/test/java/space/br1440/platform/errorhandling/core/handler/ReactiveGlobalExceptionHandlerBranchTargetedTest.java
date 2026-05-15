package space.br1440.platform.errorhandling.core.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;
import space.br1440.platform.errorhandling.webflux.testing.support.TestUnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveGlobalExceptionHandler — branch-targeted (survivor kills)")
class ReactiveGlobalExceptionHandlerBranchTargetedTest {

    private static final String CORRELATION_ID = "branch-targeted-corr-id";
    private static final String APPLICATION_NAME = "branch-app";

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    private static ReactiveGlobalExceptionHandler createHandler(@NonNull TopExceptionHandlingStrategy strategy,
                                                                @NonNull List<ErrorEntryEnricher> enrichers,
                                                                @NonNull ExceptionMetricRecorder recorder) {
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
        return ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(strategy))
                .entryEnrichers(enrichers)
                .recorder(recorder)
                .applicationName(APPLICATION_NAME)
                .unknownHandler(new TestUnknownExceptionHandler())
                .build();
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/branch")
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .build());
    }

    @Nested
    @DisplayName("Response headers contract")
    class ResponseHeaders {

        @Test
        @DisplayName("Content-Type равен application/problem+json (не отбрасывается мутацией)")
        void contentType_isProblemJson() {
            ReactiveGlobalExceptionHandler handler = createHandler(
                    fixedStrategy(HttpStatus.BAD_REQUEST, "BAD"),
                    Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            assertThat(ex.getResponse().getHeaders().getContentType())
                    .as("Content-Type должен быть application/problem+json")
                    .isNotNull()
                    .satisfies(ct -> {
                        assertThat(ct.getType()).isEqualTo("application");
                        assertThat(ct.getSubtype()).isEqualTo("problem+json");
                    });
        }

        @Test
        @DisplayName("X-Correlation-Id ответа совпадает со входящим")
        void correlationId_setOnResponse() {
            ReactiveGlobalExceptionHandler handler = createHandler(
                    fixedStrategy(HttpStatus.BAD_REQUEST, "BAD"),
                    Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            assertThat(ex.getResponse().getHeaders().getFirst("X-Correlation-Id"))
                    .isEqualTo(CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("Retry-After header (boundary: retryDelaySeconds > 0)")
    class RetryAfterHeader {

        @Test
        @DisplayName("retryable entry с positive delay → header Retry-After присутствует")
        void retryableWithPositiveDelay_setsRetryAfter() {
            RetryInfoDetail retry = new RetryInfoDetail(Duration.ofSeconds(5));
            TopExceptionHandlingStrategy strategy = customEntryStrategy(
                    ErrorEntry.builder()
                            .httpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .errorCode("RETRYABLE")
                            .message("retry me")
                            .details(List.of(retry))
                            .build());
            ReactiveGlobalExceptionHandler handler = createHandler(
                    strategy, Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            String retryAfter = ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            assertThat(retryAfter)
                    .as("Retry-After должен быть установлен при retryDelay > 0")
                    .isNotNull()
                    .isEqualTo("5");
        }

        @Test
        @DisplayName("Не-retryable entry → header Retry-After отсутствует")
        void nonRetryable_omitsRetryAfter() {
            TopExceptionHandlingStrategy strategy = fixedStrategy(HttpStatus.BAD_REQUEST, "NOT_RETRY");
            ReactiveGlobalExceptionHandler handler = createHandler(
                    strategy, Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                    .as("Retry-After не должен присутствовать для non-retryable error")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("enrichEntry: RequestContext / domain / requestURI branches")
    class EnrichEntryBranches {

        @Test
        @DisplayName("entry с уже существующим RequestContext — correlation-id сохраняется (не перезаписывается)")
        void existingRequestContext_correlationIdPreserved() {
            String preExistingCorr = "pre-existing-corr";
            RequestContext rc = RequestContext.builder().correlationId(preExistingCorr).build();
            TopExceptionHandlingStrategy strategy = customEntryStrategy(
                    ErrorEntry.builder()
                            .httpStatus(HttpStatus.BAD_REQUEST)
                            .errorCode("WITH_RC")
                            .message("m")
                            .requestContext(rc)
                            .build());
            ReactiveGlobalExceptionHandler handler = createHandler(
                    strategy, Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            String body = ex.getResponse().getBodyAsString().block();
            assertThat(body).contains(preExistingCorr);
        }

        @Test
        @DisplayName("entry без RequestContext — создан новый с correlation-id из request")
        void missingRequestContext_correlationIdCreatedFromRequest() {
            TopExceptionHandlingStrategy strategy = customEntryStrategy(
                    ErrorEntry.builder()
                            .httpStatus(HttpStatus.BAD_REQUEST)
                            .errorCode("NO_RC")
                            .message("m")
                            .build());
            ReactiveGlobalExceptionHandler handler = createHandler(
                    strategy, Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            String body = ex.getResponse().getBodyAsString().block();
            assertThat(body).contains(CORRELATION_ID);
        }

        @Test
        @DisplayName("entry без domain — domain заменяется на application name")
        void emptyDomain_replacedWithApplicationName() {
            TopExceptionHandlingStrategy strategy = customEntryStrategy(
                    ErrorEntry.builder()
                            .httpStatus(HttpStatus.BAD_REQUEST)
                            .errorCode("NO_DOMAIN")
                            .message("m")
                            .build());
            ReactiveGlobalExceptionHandler handler = createHandler(
                    strategy, Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            String body = ex.getResponse().getBodyAsString().block();
            assertThat(body).contains("\"domain\":\"" + APPLICATION_NAME + "\"");
        }

        @Test
        @DisplayName("entry с непустым domain — domain НЕ перезаписывается")
        void presentDomain_preserved() {
            TopExceptionHandlingStrategy strategy = customEntryStrategy(
                    ErrorEntry.builder()
                            .httpStatus(HttpStatus.BAD_REQUEST)
                            .errorCode("HAS_DOMAIN")
                            .message("m")
                            .domain("my-custom-domain")
                            .build());
            ReactiveGlobalExceptionHandler handler = createHandler(
                    strategy, Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            String body = ex.getResponse().getBodyAsString().block();
            assertThat(body).contains("\"domain\":\"my-custom-domain\"");
            assertThat(body).doesNotContain("\"domain\":\"" + APPLICATION_NAME + "\"");
        }

        @Test
        @DisplayName("entry без requestURI — requestURI заменяется на путь exchange")
        void emptyRequestURI_replacedWithExchangePath() {
            TopExceptionHandlingStrategy strategy = customEntryStrategy(
                    ErrorEntry.builder()
                            .httpStatus(HttpStatus.BAD_REQUEST)
                            .errorCode("NO_URI")
                            .message("m")
                            .build());
            ReactiveGlobalExceptionHandler handler = createHandler(
                    strategy, Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            String body = ex.getResponse().getBodyAsString().block();
            assertThat(body).contains("/api/branch");
        }

        @Test
        @DisplayName("entry с непустым requestURI — requestURI НЕ перезаписывается")
        void presentRequestURI_preserved() {
            TopExceptionHandlingStrategy strategy = customEntryStrategy(
                    ErrorEntry.builder()
                            .httpStatus(HttpStatus.BAD_REQUEST)
                            .errorCode("HAS_URI")
                            .message("m")
                            .requestURI("/different/preserved-path")
                            .build());
            ReactiveGlobalExceptionHandler handler = createHandler(
                    strategy, Collections.emptyList(), new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            String body = ex.getResponse().getBodyAsString().block();
            assertThat(body).contains("/different/preserved-path");
        }
    }

    @Nested
    @DisplayName("Metric snapshot — math mutators: durationNanos > 0 + path/strategy fields")
    class MetricSnapshotFields {

        @Test
        @DisplayName("Snapshot.durationNanos > 0 и в разумных рамках (math mutator nanoTime - startNanos)")
        void snapshot_durationNanosPositiveAndBounded() {
            AtomicReference<ExceptionMetricSnapshot> captured = new AtomicReference<>();
            ExceptionMetricRecorder recorder = capturingRecorder(captured);
            ReactiveGlobalExceptionHandler handler = createHandler(
                    fixedStrategy(HttpStatus.BAD_REQUEST, "DUR"),
                    Collections.emptyList(), recorder);
            MockServerWebExchange ex = exchange();

            long upperBoundNanos = Duration.ofSeconds(5).toNanos();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            assertThat(captured.get()).isNotNull();
            long duration = captured.get().durationNanos();
            assertThat(duration)
                    .as("durationNanos должен быть положительным")
                    .isPositive()
                    .as("durationNanos должен быть < 5 секунд (защита от MathMutator '-' → '+')")
                    .isLessThan(upperBoundNanos);
        }

        @Test
        @DisplayName("Snapshot.requestPath равен пути exchange")
        void snapshot_requestPathEqualsExchangePath() {
            AtomicReference<ExceptionMetricSnapshot> captured = new AtomicReference<>();
            ReactiveGlobalExceptionHandler handler = createHandler(
                    fixedStrategy(HttpStatus.BAD_REQUEST, "PATH"),
                    Collections.emptyList(), capturingRecorder(captured)
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            assertThat(captured.get().requestPath()).isEqualTo("/api/branch");
        }

        @Test
        @DisplayName("Snapshot.exceptionType равен FQCN исключения")
        void snapshot_exceptionTypeEqualsFqcn() {
            AtomicReference<ExceptionMetricSnapshot> captured = new AtomicReference<>();
            ReactiveGlobalExceptionHandler handler = createHandler(
                    fixedStrategy(HttpStatus.BAD_REQUEST, "TYPE"),
                    Collections.emptyList(), capturingRecorder(captured)
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new IllegalStateException("x"))).verifyComplete();

            assertThat(captured.get().exceptionType())
                    .isEqualTo(IllegalStateException.class.getName());
        }

        @Test
        @DisplayName("Snapshot.errorCode равен errorCode из entry")
        void snapshot_errorCodeEqualsEntryCode() {
            AtomicReference<ExceptionMetricSnapshot> captured = new AtomicReference<>();
            ReactiveGlobalExceptionHandler handler = createHandler(
                    fixedStrategy(HttpStatus.BAD_REQUEST, "CODE_X"),
                    Collections.emptyList(), capturingRecorder(captured)
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            assertThat(captured.get().errorCode()).isEqualTo("CODE_X");
        }

        @Test
        @DisplayName("Snapshot.httpStatusCode равен HTTP-статусу entry")
        void snapshot_httpStatusCodeEqualsEntryStatus() {
            AtomicReference<ExceptionMetricSnapshot> captured = new AtomicReference<>();
            ReactiveGlobalExceptionHandler handler = createHandler(
                    fixedStrategy(HttpStatus.UNPROCESSABLE_ENTITY, "X"),
                    Collections.emptyList(), capturingRecorder(captured)
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            assertThat(captured.get().httpStatusCode()).isEqualTo(422);
        }

        @Test
        @DisplayName("rootCauseType=null когда cause-chain состоит только из самого исключения")
        void snapshot_rootCauseType_nullWhenNoNestedCause() {
            AtomicReference<ExceptionMetricSnapshot> captured = new AtomicReference<>();
            ReactiveGlobalExceptionHandler handler = createHandler(
                    fixedStrategy(HttpStatus.BAD_REQUEST, "X"),
                    Collections.emptyList(), capturingRecorder(captured)
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("flat-no-cause")))
                    .verifyComplete();

            assertThat(captured.get().rootCauseType())
                    .as("Без вложенного cause rootCauseType == null")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("recordHandled vs recordUnhandled — branch coverage")
    class HandledUnhandledBranches {

        @Test
        @DisplayName("Strategy матчит → ровно одна запись в recordHandled, ноль в recordUnhandled")
        void handledPath_recordsHandled_noUnhandled() {
            List<ExceptionMetricSnapshot> handled = new ArrayList<>();
            List<ExceptionMetricSnapshot> unhandled = new ArrayList<>();
            ReactiveGlobalExceptionHandler handler = createHandler(
                    fixedStrategy(HttpStatus.BAD_REQUEST, "H"),
                    Collections.emptyList(),
                    splitRecorder(handled, unhandled)
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            assertThat(handled).hasSize(1);
            assertThat(unhandled).isEmpty();
            assertThat(handled.getFirst().strategyName()).isNotEqualTo("unknown");
        }

        @Test
        @DisplayName("Нет matching strategy → ровно одна запись в recordUnhandled, ноль в recordHandled")
        void unhandledPath_recordsUnhandled_noHandled() {
            List<ExceptionMetricSnapshot> handled = new ArrayList<>();
            List<ExceptionMetricSnapshot> unhandled = new ArrayList<>();
            ReactiveGlobalExceptionHandler handler = createHandler(
                    nonSupportingStrategy(),
                    Collections.emptyList(),
                    splitRecorder(handled, unhandled)
            );
            MockServerWebExchange ex = exchange();

            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            assertThat(handled).isEmpty();
            assertThat(unhandled).hasSize(1);
            assertThat(unhandled.getFirst().strategyName()).isEqualTo("unknown");
        }
    }

    private static TopExceptionHandlingStrategy fixedStrategy(@NonNull HttpStatus status, @NonNull String code) {
        return customEntryStrategy(ErrorEntry.builder()
                .httpStatus(status)
                .errorCode(code)
                .message("test message")
                .build());
    }

    private static TopExceptionHandlingStrategy customEntryStrategy(@NonNull ErrorEntry entry) {
        return new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NonNull Exception exception) {
                return true;
            }

            @Override
            @NonNull
            public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext ctx) {
                return entry;
            }

            @Override
            @NonNull
            public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry e) {
                return LogPolicy.WARN;
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
    }

    private static TopExceptionHandlingStrategy nonSupportingStrategy() {
        return new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NonNull Exception exception) {
                return false;
            }

            @Override
            @NonNull
            public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext ctx) {
                throw new IllegalStateException("must not be called");
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
    }

    private static ExceptionMetricRecorder capturingRecorder(@NonNull AtomicReference<ExceptionMetricSnapshot> ref) {
        return new ExceptionMetricRecorder() {
            @Override
            public void recordHandled(@NonNull ExceptionMetricSnapshot s) {
                ref.set(s);
            }

            @Override
            public void recordUnhandled(@NonNull ExceptionMetricSnapshot s) {
                ref.set(s);
            }
        };
    }

    private static ExceptionMetricRecorder splitRecorder(@NonNull List<ExceptionMetricSnapshot> handled,
                                                         @NonNull List<ExceptionMetricSnapshot> unhandled) {
        return new ExceptionMetricRecorder() {
            @Override
            public void recordHandled(@NonNull ExceptionMetricSnapshot s) {
                handled.add(s);
            }

            @Override
            public void recordUnhandled(@NonNull ExceptionMetricSnapshot s) {
                unhandled.add(s);
            }
        };
    }
}
