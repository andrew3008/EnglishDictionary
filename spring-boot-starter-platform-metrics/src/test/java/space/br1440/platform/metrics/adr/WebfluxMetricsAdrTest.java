package space.br1440.platform.metrics.adr;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.adr.support.MetricsTestSupport;
import space.br1440.platform.metrics.adr.support.PlatformMetricsFactory;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;
import space.br1440.platform.metrics.webflux.WebfluxMetricsFilter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ADR: HTTP metrics WebFlux")
class WebfluxMetricsAdrTest {

    private MeterRegistry registry;
    private PlatformMetrics platformMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        platformMetrics = PlatformMetricsFactory.create(registry);
    }

    private WebfluxMetricsFilter buildFilter(HttpMetricProperties.PathMode mode) {
        HttpMetricProperties props = new HttpMetricProperties()
                .setTimerMetricName("platform.http.server.requests")
                .setCounterMetricName("platform.http.requests")
                .setPathMode(mode);
        return new WebfluxMetricsFilter(platformMetrics, props);
    }

    private void runFilter(WebfluxMetricsFilter filter, MockServerWebExchange exchange) {
        WebFilterChain chain = serverWebExchange -> {
            if (exchange.getResponse().getStatusCode() == null) {
                exchange.getResponse().setStatusCode(HttpStatus.OK);
            }
            return serverWebExchange.getResponse().setComplete();
        };
        filter.filter(exchange, chain).block();
    }

    @Nested
    @DisplayName("PathMode.NORMALIZED")
    class NormalizedMode {

        @Test
        void counterAndTimerWithMvcTemplate() {
            WebfluxMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.NORMALIZED);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users/42").build());
            exchange.getAttributes().put(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/users/{id}");
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            runFilter(filter, exchange);

            assertThat(MetricsTestSupport.findCounter(registry, "platform.http.requests",
                    MetricTags.METHOD, "GET", MetricTags.PATH, "/api/users/{id}", MetricTags.STATUS, "200").count())
                    .isEqualTo(1.0);
            assertThat(MetricsTestSupport.findTimer(registry, "platform.http.server.requests",
                    MetricTags.METHOD, "GET", MetricTags.PATH, "/api/users/{id}", MetricTags.STATUS, "200").count())
                    .isEqualTo(1L);
        }

        @Test
        void notFoundPath() {
            WebfluxMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.NORMALIZED);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/missing").build());
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            runFilter(filter, exchange);

            assertThat(MetricsTestSupport.findCounter(registry, "platform.http.requests",
                    MetricTags.METHOD, "GET", MetricTags.PATH, "NOT_FOUND", MetricTags.STATUS, "404").count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("PathMode.RAW")
    class RawMode {

        @Test
        void pathIsRealUrl() {
            WebfluxMetricsFilter filter = buildFilter(HttpMetricProperties.PathMode.RAW);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users/42").build());
            exchange.getAttributes().put(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/users/{id}");
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            runFilter(filter, exchange);

            assertThat(MetricsTestSupport.findCounter(registry, "platform.http.requests",
                    MetricTags.METHOD, "GET", MetricTags.PATH, "/api/users/42", MetricTags.STATUS, "200").count())
                    .isEqualTo(1.0);
        }
    }
}
