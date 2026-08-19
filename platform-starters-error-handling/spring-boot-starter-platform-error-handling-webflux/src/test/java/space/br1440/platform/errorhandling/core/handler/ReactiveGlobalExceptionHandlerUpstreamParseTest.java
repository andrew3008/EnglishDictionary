package space.br1440.platform.errorhandling.core.handler;

import io.micrometer.context.ContextRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Hooks;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.autoconfigure.MdcThreadLocalAccessor;
import space.br1440.platform.errorhandling.core.enricher.UpstreamIdentityEnricher;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.strategy.top.WebFluxExceptionStrategy;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.http.ErrorMediaTypes;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("handler-side async upstream JSON parsing")
class ReactiveGlobalExceptionHandlerUpstreamParseTest {

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    private static ReactiveGlobalExceptionHandler createHandler(@NonNull ErrorHandlingProperties properties,
                                                                @NonNull ExceptionMetricRecorder recorder,
                                                                @NonNull List<ErrorEntryEnricher> enrichers) {
        return ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(webClientStrategy()))
                .unknownHandler(fixedUnknownHandler())
                .recorder(recorder)
                .entryEnrichers(enrichers)
                .applicationName("test-app")
                .build();
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/c5")
                        .header("X-Correlation-Id", "c5-corr-id")
                        .build());
    }

    private static TopExceptionHandlingStrategy webClientStrategy() {
        return new WebFluxExceptionStrategy(new CauseChainResolver(List.of()));
    }

    private static WebClientResponseException upstreamWith(@NonNull String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE);

        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://upstream/api/x"));
        return WebClientResponseException.create(
                500, "Server Error", headers, body, StandardCharsets.UTF_8, request
        );
    }

    @Test
    @DisplayName("handler: парсинг upstream JSON async, errorCode/domain попадают в ответ через UpstreamIdentityEnricher")
    void handlerAsync_enrichesUpstreamErrorCode() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        ReactiveGlobalExceptionHandler handler = createHandler(
                properties, new NoopExceptionMetricRecorder(), List.of(new UpstreamIdentityEnricher())
        );
        MockServerWebExchange ex = exchange();

        WebClientResponseException upstream = upstreamWith(
                "{\"errorCode\":\"UPSTREAM_FAILURE_42\",\"domain\":\"billing-service\",\"message\":\"u\",\"httpCode\":500}"
        );

        // when
        StepVerifier.create(handler.handle(ex, upstream)).verifyComplete();

        // then
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body)
                .as("upstream errorCode должен быть подхвачен async-парсером handler'а")
                .contains("UPSTREAM_FAILURE_42");
        assertThat(body)
                .as("top-level domain должен быть заменён именем прямого upstream-сервиса")
                .contains("\"domain\":\"billing-service\"");
    }

    @Test
    @DisplayName("без UpstreamIdentityEnricher в цепочке: identity распознан, но не применён к ответу")
    void withoutEnricher_identityIsNotApplied() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        ReactiveGlobalExceptionHandler handler = createHandler(
                properties, new NoopExceptionMetricRecorder(), Collections.emptyList());
        MockServerWebExchange ex = exchange();

        WebClientResponseException upstream = upstreamWith(
                "{\"errorCode\":\"SHOULD_NOT_LEAK\",\"message\":\"u\"}");

        // when
        StepVerifier.create(handler.handle(ex, upstream)).verifyComplete();

        // then
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body)
                .as("без enricher'а в цепочке upstream errorCode не должен попасть в ответ")
                .doesNotContain("SHOULD_NOT_LEAK");
    }

    @Test
    @DisplayName("Не-WebClient исключение: deriveUpstreamContextAsync — no-op без offload-таймаута")
    void nonWebClientException_isNoOp() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        AtomicInteger timeoutCount = new AtomicInteger(0);
        ExceptionMetricRecorder recorder = new ExceptionMetricRecorder() {
            @Override
            public void recordHandled(@NonNull ExceptionMetricSnapshot s) {
            }

            @Override
            public void recordUnhandled(@NonNull ExceptionMetricSnapshot s) {
            }

            @Override
            public void recordUpstreamParseTimeout() {
                timeoutCount.incrementAndGet();
            }
        };

        ReactiveGlobalExceptionHandler handler = ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(catchAllStrategy(HttpStatus.INTERNAL_SERVER_ERROR, "GENERIC")))
                .unknownHandler(fixedUnknownHandler())
                .recorder(recorder)
                .entryEnrichers(Collections.emptyList())
                .applicationName("test-app")
                .build();

        MockServerWebExchange ex = exchange();

        // when
        StepVerifier.create(handler.handle(ex, new RuntimeException("not a webclient error")))
                .verifyComplete();

        // then
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(timeoutCount.get())
                .as("recordUpstreamParseTimeout не должен инкрементироваться для не-WebClient ошибок")
                .isZero();
    }

    @Test
    @DisplayName("Парсинг идёт на boundedElastic-потоке (sanity-check)")
    void parsingHappensOnBoundedElastic() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();

        try {
            AtomicReference<String> threadInEnricher = new AtomicReference<>();
            ErrorEntryEnricher capturingEnricher = (entry, ctx) -> {
                threadInEnricher.set(Thread.currentThread().getName());
                return entry;
            };

            ReactiveGlobalExceptionHandler handler = createHandler(
                    properties, new NoopExceptionMetricRecorder(), List.of(capturingEnricher));
            MockServerWebExchange ex = exchange();

            WebClientResponseException upstream = upstreamWith(
                    "{\"errorCode\":\"X\",\"message\":\"u\",\"httpCode\":500}");

            // when
            StepVerifier.create(handler.handle(ex, upstream)).verifyComplete();

            // then
            assertThat(threadInEnricher.get())
                    .as("После async upstream-парсинга continuation должен идти на boundedElastic")
                    .isNotNull()
                    .containsIgnoringCase("boundedElastic");
        } finally {
            reactor.core.publisher.Hooks.disableAutomaticContextPropagation();
        }
    }

    @Test
    @DisplayName("Таймаут парсинга → fail-open + recordUpstreamParseTimeout (контракт инвариантен по тимингу)")
    void parseTimeout_failOpen_recordsMetric() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        AtomicInteger timeoutCount = new AtomicInteger(0);
        ExceptionMetricRecorder recorder = new ExceptionMetricRecorder() {
            @Override
            public void recordHandled(@NonNull ExceptionMetricSnapshot s) {
            }

            @Override
            public void recordUnhandled(@NonNull ExceptionMetricSnapshot s) {
            }

            @Override
            public void recordUpstreamParseTimeout() {
                timeoutCount.incrementAndGet();
            }
        };

        ReactiveGlobalExceptionHandler handler = createHandler(
                properties, recorder, List.of(new UpstreamIdentityEnricher()));
        MockServerWebExchange ex = exchange();

        WebClientResponseException upstream = upstreamWith(
                "{\"errorCode\":\"WILL_TIMEOUT\",\"message\":\"u\",\"httpCode\":500}");

        // when
        StepVerifier.create(handler.handle(ex, upstream)).verifyComplete();

        // then
        String body = ex.getResponse().getBodyAsString().block();
        boolean leaked = body != null && body.contains("WILL_TIMEOUT");
        boolean recorded = timeoutCount.get() > 0;

        assertThat(leaked ^ recorded)
                .as("fail-open контракт: либо парсинг успел (errorCode в ответе, метрика 0), "
                                + "либо таймаут (errorCode не в ответе, метрика > 0). leaked=%s, recorded=%s",
                        leaked, recorded)
                .isTrue();

        if (recorded) {
            assertThat(timeoutCount.get())
                    .as("recordUpstreamParseTimeout инкрементируется ровно один раз на запрос")
                    .isEqualTo(1);
        }
    }

    private static TopExceptionHandlingStrategy catchAllStrategy(@NonNull HttpStatus status, @NonNull String code) {
        return new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NonNull Exception exception) {
                return true;
            }

            @Override
            @NonNull
            public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext ctx) {
                return ErrorEntry.builder()
                        .httpStatus(status)
                        .errorCode(code)
                        .message("test")
                        .build();
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

    private static UnknownExceptionHandler fixedUnknownHandler() {
        return new UnknownExceptionHandler() {
            @Override
            public @NotNull ErrorEntry handle(@NotNull Exception exception, @NotNull ErrorHandlingContext context) {
                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .errorCode("UNKNOWN")
                        .message("Unexpected")
                        .build();
            }

            @Override
            public @NotNull LogPolicy logPolicy(@NotNull Exception exception, @NotNull ErrorEntry errorEntry) {
                return LogPolicy.ERROR;
            }
        };
    }
}
