package space.br1440.platform.errorhandling.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = WebFluxErrorHandlingE2ESmokeTest.SmokeApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=webflux-smoke",
                "platform.error-handling.enabled=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration"
        })
@DisplayName("WebFlux E2E smoke (production-contract)")
class WebFluxErrorHandlingE2ESmokeTest {

    @Autowired
    private WebTestClient webTestClient;

    @Nested
    @DisplayName("HTTP contract (RFC 7807 + correlation-id)")
    class HttpContract {

        @Test
        @DisplayName("404 → Content-Type=application/problem+json")
        void notFound_returnsProblemJson() {
            // when
            var response = webTestClient.get().uri("/smoke/404")
                    .exchange();

            // then
            response.expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
                    .expectHeader().contentTypeCompatibleWith(parseProblemJson())
                    .expectBody().jsonPath("$").isNotEmpty();
        }

        @Test
        @DisplayName("400 → Content-Type=application/problem+json")
        void badRequest_returnsProblemJson() {
            // when
            var response = webTestClient.get().uri("/smoke/400")
                    .exchange();

            // then
            response.expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
                    .expectHeader().contentTypeCompatibleWith(parseProblemJson());
        }

        @Test
        @DisplayName("500 (NPE) → Content-Type=application/problem+json")
        void internalError_returnsProblemJson() {
            // when
            var response = webTestClient.get().uri("/smoke/500")
                    .exchange();

            // then
            response.expectStatus().is5xxServerError()
                    .expectHeader().contentTypeCompatibleWith(parseProblemJson());
        }

        @Test
        @DisplayName("503 → Content-Type=application/problem+json")
        void serviceUnavailable_returnsProblemJson() {
            // when
            var response = webTestClient.get().uri("/smoke/503")
                    .exchange();

            // then
            response.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().contentTypeCompatibleWith(parseProblemJson());
        }

        @Test
        @DisplayName("Принятый X-Correlation-Id переносится либо в response-header, либо в body.traceId")
        void correlationIdPropagation_returnedToClient() {
            // given
            String inputCid = "smoke-corr-id-12345";

            // when
            EntityExchangeResult<byte[]> result = webTestClient.get().uri("/smoke/404")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, inputCid)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
                    .expectBody()
                    .returnResult();

            // then
            String body = result.getResponseBody() != null
                    ? new String(result.getResponseBody())
                    : "";
            String headerCid = result.getResponseHeaders().getFirst(ErrorHandlingHeaders.X_CORRELATION_ID);

            assertThat(headerCid != null && headerCid.equals(inputCid)
                    || body.contains(inputCid))
                    .as("correlation-id propagation: header='%s' bodySnippet='%s'",
                            headerCid, snippet(body))
                    .isTrue();
        }

        @Test
        @DisplayName("Невалидный X-Correlation-Id (overlength) отклоняется + сервис не падает")
        void invalidCorrelationId_isRejectedButRequestProceeds() {
            // given
            String overlengthCid = "a".repeat(1024);

            // when
            var response = webTestClient.get().uri("/smoke/404")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, overlengthCid)
                    .exchange();

            // then
            response.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private static MediaType parseProblemJson() {
        return MediaType.parseMediaType("application/problem+json");
    }

    private static String snippet(String s) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            ReactiveSecurityAutoConfiguration.class,
            ReactiveUserDetailsServiceAutoConfiguration.class
    })
    static class SmokeApp {

        @RestController
        @RequestMapping("/smoke")
        static class SmokeController {

            @GetMapping("/404")
            Mono<String> notFound() {
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "smoke not found"));
            }

            @GetMapping("/400")
            Mono<String> badRequest() {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "smoke bad request"));
            }

            @GetMapping("/500")
            Mono<String> internalError() {
                return Mono.error(new NullPointerException("smoke npe"));
            }

            @GetMapping("/503")
            Mono<String> serviceUnavailable() {
                HttpHeaders headers = new HttpHeaders();
                headers.set(ErrorHandlingHeaders.RETRY_AFTER, "30");
                return Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "smoke unavailable", null));
            }
        }
    }
}
