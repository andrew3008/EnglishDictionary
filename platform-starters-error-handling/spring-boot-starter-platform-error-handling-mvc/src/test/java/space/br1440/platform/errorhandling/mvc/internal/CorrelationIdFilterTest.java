package space.br1440.platform.errorhandling.mvc.internal;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys;
import space.br1440.platform.errorhandling.core.util.CorrelationIdResolver;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        CorrelationIdResolver resolver = new CorrelationIdResolver(
                new ErrorHandlingProperties().getCorrelationId(),
                false
        );
        filter = new CorrelationIdFilter(resolver, null);
    }

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("correlationId из X-Correlation-Id header кладётся в MDC, response, request attribute")
    void existingHeader_propagatedToAllChannels() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(response.getHeader(ErrorHandlingHeaders.X_CORRELATION_ID)).isEqualTo("abc-123");
        assertThat(request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE))
                .isEqualTo("abc-123");
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Без header — генерируется UUID, который виден везде")
    void noHeader_generatedUuidPropagated() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, chain);

        // then
        String headerValue = response.getHeader(ErrorHandlingHeaders.X_CORRELATION_ID);
        Object attrValue = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);

        assertThat(headerValue).isNotBlank();
        assertThat(attrValue).isEqualTo(headerValue);
    }

    @Test
    @DisplayName("MDC содержит correlationId во время вызова chain — а после фильтра очищен")
    void mdc_isolatedToFilterScope() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "scope-test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // chain как мини-контроллер: проверяет MDC прямо в момент вызова
        String[] mdcDuringChain = new String[1];
        FilterChain chain = (req, resp) -> {
            mdcDuringChain[0] = MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID);
        };

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(mdcDuringChain[0]).isEqualTo("scope-test");
        assertThat(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("MDC.remove не затрагивает чужие ключи (Security/tracing/business)")
    void otherMdcKeys_preserved() throws Exception {
        // given
        MDC.put("foreign-security-key", "auth-context-value");
        MDC.put("traceId", "trace-abc");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(MDC.get("foreign-security-key")).isEqualTo("auth-context-value");
        assertThat(MDC.get("traceId")).isEqualTo("trace-abc");
        assertThat(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("Если chain выбросил исключение, MDC всё равно очищается (try/finally)")
    void mdcCleanedEvenOnException() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "error-test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {
            throw new RuntimeException("simulated downstream failure");
        };

        // when
        try {
            filter.doFilter(request, response, chain);
        } catch (Exception ignored) {
        }

        // then
        assertThat(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("joint-режим: correlationId берётся из tracingRequestContextSupplier, MDC и response-заголовок не пишутся")
    void jointMode_readsFromTracingSupplier_skipsMdcAndResponseHeader() throws Exception {
        // given
        CorrelationIdResolver resolver = new CorrelationIdResolver(
                new ErrorHandlingProperties().getCorrelationId(),
                false
        );
        CorrelationIdFilter jointFilter = new CorrelationIdFilter(
                resolver,
                () -> RequestContext.builder().correlationId("joint-corr-id").build()
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcDuringChain = new String[1];
        FilterChain chain = (req, resp) -> mdcDuringChain[0] = MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID);

        // when
        jointFilter.doFilter(request, response, chain);

        // then
        assertThat(request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE)).isEqualTo("joint-corr-id");
        assertThat(mdcDuringChain[0]).isNull();
        assertThat(response.getHeader(ErrorHandlingHeaders.X_CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("Фильтр не затирает существующий response-заголовок X-Correlation-Id")
    void doesNotOverwriteExistingResponseHeader() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "from-request");

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "preset-by-other-filter");

        FilterChain chain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(response.getHeader(ErrorHandlingHeaders.X_CORRELATION_ID))
                .isEqualTo("preset-by-other-filter");
    }
}
