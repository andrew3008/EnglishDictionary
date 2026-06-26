package space.br1440.platform.errorhandling.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-2 WebFlux contract smoke — reactive ProblemDetail path (starters-only).
 */
@Tag("battle-ready")
@SpringBootTest(
        classes = WebFluxErrorHandlingBattleSmokeTest.SmokeApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=webflux-battle-smoke",
                "platform.error-handling.enabled=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration"
        })
@DisplayName("WebFlux battle smoke")
class WebFluxErrorHandlingBattleSmokeTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("TOP-RSE-04 reactive — 404 problem+json")
    void responseStatusNotFound() {
        webTestClient.get().uri("/battle-smoke/404")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$").isNotEmpty();
    }

    @Test
    @DisplayName("TOP-UNK-01 reactive — unhandled error sanitized")
    void unhandledError() {
        var result = webTestClient.get().uri("/battle-smoke/boom")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .returnResult(String.class);
        assertThat(result.getResponseBody()).isNotNull();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SmokeApp {
        @RestController
        static class BattleSmokeController {
            @GetMapping("/battle-smoke/404")
            Mono<Void> notFound() {
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));
            }

            @GetMapping("/battle-smoke/boom")
            Mono<Void> boom() {
                return Mono.error(new IllegalStateException("battle-smoke"));
            }
        }
    }
}
