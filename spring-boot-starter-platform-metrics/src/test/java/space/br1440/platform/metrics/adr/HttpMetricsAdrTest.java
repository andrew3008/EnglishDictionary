package space.br1440.platform.metrics.adr;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.adr.support.MetricsTestSupport;
import space.br1440.platform.metrics.adr.support.PlatformMetricsFactory;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;
import space.br1440.platform.metrics.http.HttpMetricsFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест ADR: HTTP-метрики (Servlet-стек).
 *
 * <p>Контракт SRE ADR, проверяемый здесь:</p>
 * <ul>
 *   <li>{@code platform_http_requests_total} — Counter с тегами {@code method}, {@code path}, {@code status};</li>
 *   <li>{@code platform_http_server_requests_seconds} — Timer с теми же тегами;</li>
 *   <li>режим NORMALIZED: тег {@code path} содержит шаблон MVC ({@code /api/users/{id}}), не реальный URL;</li>
 *   <li>стандартные значения {@code path}: {@code REDIRECTION}, {@code NOT_FOUND}, {@code ROOT};</li>
 *   <li>режим RAW: тег {@code path} содержит реальный URL запроса;</li>
 *   <li>оба режима не пересекаются при проверке кардинальности тегов.</li>
 * </ul>
 */
@DisplayName("ADR: HTTP-метрики (Servlet)")
class HttpMetricsAdrTest {

    private MeterRegistry registry;
    private PlatformMetrics platformMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        platformMetrics = PlatformMetricsFactory.create(registry);
    }

    private HttpMetricsFilter buildFilter(HttpMetricProperties.PathMode mode) {
        HttpMetricProperties props = new HttpMetricProperties()
                .setTimerMetricName("platform.http.server.requests")
                .setCounterMetricName("platform.http.requests")
                .setPathMode(mode);
        return new HttpMetricsFilter(platformMetrics, props);
    }

    private MockHttpServletResponse doFilter(HttpMetricsFilter filter,
                                             MockHttpServletRequest request,
                                             MockHttpServletResponse response) throws Exception {
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. NORMALIZED mode: шаблон MVC
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PathMode.NORMALIZED")
    class NormalizedMode {

        @Test
        @DisplayName("counter и timer с тегами method, path=/api/users/{id}, status=200")
        void counterAndTimerWithMvcTemplate() throws Exception {
            HttpMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.NORMALIZED);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/42");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/users/{id}");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(200);

            doFilter(filter, request, response);

            Counter counter = MetricsTestSupport.findCounter(registry, "platform.http.requests",
                    MetricTags.METHOD, "GET",
                    MetricTags.PATH, "/api/users/{id}",
                    MetricTags.STATUS, "200");
            assertThat(counter.count()).isEqualTo(1.0);

            Timer timer = MetricsTestSupport.findTimer(registry, "platform.http.server.requests",
                    MetricTags.METHOD, "GET",
                    MetricTags.PATH, "/api/users/{id}",
                    MetricTags.STATUS, "200");
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("path=NOT_FOUND при status=404 и отсутствии bestMatchingPattern")
        void notFoundPath() throws Exception {
            HttpMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.NORMALIZED);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missing");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(404);

            doFilter(filter, request, response);

            Counter counter = MetricsTestSupport.findCounter(registry, "platform.http.requests",
                    MetricTags.METHOD, "GET",
                    MetricTags.PATH, "NOT_FOUND",
                    MetricTags.STATUS, "404");
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("path=REDIRECTION при status=301")
        void redirectionPath301() throws Exception {
            HttpMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.NORMALIZED);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/old-path");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(301);

            doFilter(filter, request, response);

            Counter counter = MetricsTestSupport.findCounter(registry, "platform.http.requests",
                    MetricTags.METHOD, "GET",
                    MetricTags.PATH, "REDIRECTION",
                    MetricTags.STATUS, "301");
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("path=REDIRECTION при status=302/303/307/308")
        void redirectionPathAllCodes() throws Exception {
            HttpMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.NORMALIZED);

            for (int status : new int[]{302, 303, 307, 308}) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/redirect");
                MockHttpServletResponse response = new MockHttpServletResponse();
                response.setStatus(status);
                doFilter(filter, request, response);
            }

            // Все 4 записи — REDIRECTION (разные status-теги, но одинаковый path)
            long redirectionCount = registry.find("platform.http.requests")
                    .counters().stream()
                    .filter(c -> "REDIRECTION".equals(c.getId().getTag(MetricTags.PATH)))
                    .mapToLong(c -> (long) c.count())
                    .sum();
            assertThat(redirectionCount).isEqualTo(4L);
        }

        @Test
        @DisplayName("status=304 (Not Modified) НЕ попадает в REDIRECTION")
        void notModifiedIsNotRedirection() throws Exception {
            HttpMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.NORMALIZED);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resource");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(304);

            doFilter(filter, request, response);

            // Не должно быть REDIRECTION с 304
            boolean hasRedirectionWith304 = registry.find("platform.http.requests")
                    .counters().stream()
                    .anyMatch(c -> "REDIRECTION".equals(c.getId().getTag(MetricTags.PATH))
                            && "304".equals(c.getId().getTag(MetricTags.STATUS)));
            assertThat(hasRedirectionWith304).isFalse();
        }

        @Test
        @DisplayName("несколько запросов накапливают счётчик")
        void multipleRequestsAccumulate() throws Exception {
            HttpMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.NORMALIZED);

            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/orders");
                MockHttpServletResponse response = new MockHttpServletResponse();
                response.setStatus(201);
                doFilter(filter, request, response);
            }

            Counter counter = MetricsTestSupport.findCounter(registry, "platform.http.requests",
                    MetricTags.METHOD, "POST",
                    MetricTags.PATH, "/api/orders",
                    MetricTags.STATUS, "201");
            assertThat(counter.count()).isEqualTo(5.0);

            Timer timer = MetricsTestSupport.findTimer(registry, "platform.http.server.requests",
                    MetricTags.METHOD, "POST",
                    MetricTags.PATH, "/api/orders",
                    MetricTags.STATUS, "201");
            assertThat(timer.count()).isEqualTo(5L);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. RAW mode: реальный URL
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PathMode.RAW")
    class RawMode {

        @Test
        @DisplayName("тег path содержит реальный URL, не MVC-шаблон")
        void pathIsRealUrl() throws Exception {
            HttpMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.RAW);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/42");
            request.setServletPath("/api/users/42");
            // даже если bestMatchingPattern присутствует — в RAW он игнорируется
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/users/{id}");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(200);

            doFilter(filter, request, response);

            // В RAW-режиме path = реальный URL "/api/users/42", не шаблон
            Counter counter = MetricsTestSupport.findCounter(registry, "platform.http.requests",
                    MetricTags.METHOD, "GET",
                    MetricTags.PATH, "/api/users/42",
                    MetricTags.STATUS, "200");
            assertThat(counter.count()).isEqualTo(1.0);

            // Шаблон НЕ должен быть зарегистрирован
            assertThat(MetricsTestSupport.findCounterOrNull(registry, "platform.http.requests",
                    MetricTags.METHOD, "GET",
                    MetricTags.PATH, "/api/users/{id}",
                    MetricTags.STATUS, "200")).isNull();
        }

        @Test
        @DisplayName("контекст-путь не включается в тег path")
        void contextPathIsStripped() throws Exception {
            HttpMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.RAW);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/myapp/api/ping");
            request.setContextPath("/myapp");
            // servletPath пустой — выбор fallback через requestURI
            request.setServletPath("");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(200);

            doFilter(filter, request, response);

            // Тег path должен быть без контекста: "/api/ping", не "/myapp/api/ping"
            Counter counter = MetricsTestSupport.findCounter(registry, "platform.http.requests",
                    MetricTags.METHOD, "GET",
                    MetricTags.PATH, "/api/ping",
                    MetricTags.STATUS, "200");
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }
}
