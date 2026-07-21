package space.br1440.platform.tracing.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ReactiveCorrelationOperationsSurfaceTest {

    @Test
    void publicSurfaceContainsOnlyShapePreservingOverloads() throws Exception {
        assertThat(ReactiveCorrelationOperations.class.getDeclaredMethods()).hasSize(2);

        Method mono = ReactiveCorrelationOperations.class.getMethod(
                "withCorrelationId", String.class, Mono.class);
        Method flux = ReactiveCorrelationOperations.class.getMethod(
                "withCorrelationId", String.class, Flux.class);

        assertThat(mono.getReturnType()).isEqualTo(Mono.class);
        assertThat(flux.getReturnType()).isEqualTo(Flux.class);
    }
}
