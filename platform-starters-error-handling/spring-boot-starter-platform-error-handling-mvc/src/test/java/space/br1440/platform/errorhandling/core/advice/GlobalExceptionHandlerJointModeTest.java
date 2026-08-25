package space.br1440.platform.errorhandling.core.advice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.mock.web.MockHttpServletRequest;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;
import space.br1440.platform.errorhandling.mvc.internal.ExceptionLogger;
import space.br1440.platform.errorhandling.mvc.internal.ProblemResponseFactory;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver;
import space.br1440.platform.errorhandling.testing.support.TestUnknownExceptionHandler;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler joint mode")
class GlobalExceptionHandlerJointModeTest {

    private static final String APP_NAME = "joint-test-app";

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    @AfterEach
    void cleanupMdc() {
        MDC.clear();
    }

    private GlobalExceptionHandler jointHandler(String effectiveCorrelationId,
                                                @NonNull List<TopExceptionHandlingStrategy> strategies) {
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        UnknownExceptionHandler unknownHandler = new TestUnknownExceptionHandler();

        return new GlobalExceptionHandler(
                properties,
                strategies,
                unknownHandler,
                new NoopExceptionMetricRecorder(),
                new ProblemResponseFactory(new ErrorEntryToDtoMapper(), APP_NAME, properties),
                new ExceptionLogger(properties),
                TestCorrelationIdResolver.defaultInstance(),
                Collections.emptyList(),
                new StackHintExtractor(false, List.of()),
                Collections.emptyList(),
                () -> RequestContext.builder().correlationId(effectiveCorrelationId).build()
        );
    }

    private static MockHttpServletRequest requestWithHeader(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        if (headerValue != null) {
            request.addHeader("X-Correlation-Id", headerValue);
        }

        return request;
    }

    @Test
    @DisplayName("effective correlationId читается из tracingRequestContextSupplier, а не из request-заголовка")
    void jointMode_readsFromSupplier_ignoresRequestHeader() {
        // given
        GlobalExceptionHandler handler = jointHandler("tracing-owned-corr", List.of());

        // when
        ResponseEntity<ErrorEntryDTO> response =
                handler.handleException(new RuntimeException("test"), requestWithHeader("request-header-value"));

        // then: response echo всегда выключен в joint-режиме
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNull();
    }

    @Test
    @DisplayName("отсутствие активной корреляции (null) не приводит к сбою обработки")
    void jointMode_nullEffectiveCorrelationId_handledGracefully() {
        // given
        GlobalExceptionHandler handler = jointHandler(null, List.of());

        // when
        ResponseEntity<ErrorEntryDTO> response =
                handler.handleException(new RuntimeException("test"), requestWithHeader(null));

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNull();
    }

    @Test
    @DisplayName("safety-net ответ при сбое обработчика также не пишет X-Correlation-Id в joint-режиме")
    void jointMode_safetyNetResponse_doesNotWriteCorrelationHeader() {
        // given
        TopExceptionHandlingStrategy failingStrategy = new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NonNull Exception exception) {
                return true;
            }

            @Override
            @NonNull
            public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
                throw new RuntimeException("handler crash");
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
        GlobalExceptionHandler handler = jointHandler("tracing-owned-corr", List.of(failingStrategy));

        // when
        ResponseEntity<ErrorEntryDTO> response =
                handler.handleException(new RuntimeException("original"), requestWithHeader("request-header-value"));

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNull();
    }

    @Test
    @DisplayName("в joint-режиме correlationId, установленный tracing identity boundary в MDC "
            + "до обработки, не стирается по завершении handleException (tracing остаётся владельцем)")
    void jointMode_doesNotEraseTracingOwnedCorrelationIdFromMdc() {
        // given: tracing identity boundary уже установил correlationId в MDC до вызова хендлера
        MDC.put(ErrorHandlingMdcKeys.CORRELATION_ID, "tracing-owned-corr");
        GlobalExceptionHandler handler = jointHandler("tracing-owned-corr", List.of());

        // when
        handler.handleException(new RuntimeException("test"), requestWithHeader(null));

        // then: значение осталось нетронутым — error-handling им не управляет в joint-режиме
        assertThat(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID)).isEqualTo("tracing-owned-corr");
    }
}
