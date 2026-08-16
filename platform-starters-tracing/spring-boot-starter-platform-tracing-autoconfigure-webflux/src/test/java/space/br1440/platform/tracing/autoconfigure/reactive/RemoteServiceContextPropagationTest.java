package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import reactor.core.Disposable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

class RemoteServiceContextPropagationTest {

    @BeforeAll
    static void configurePropagation() {
        RemoteServiceContextPropagation.registerIfAbsent();
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void successSignal_restores_MDC_only_for_callback_duration() {
        String observed = Mono.deferContextual(ignored -> Mono.fromSupplier(
                        () -> MDC.get(TracingMdcKeys.REMOTE_SERVICE)))
                .contextWrite(RemoteServiceReactorContext.contextWrite("billing"))
                .block();

        assertThat(observed).isEqualTo("billing");
        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
    }

    @Test
    void errorSignal_does_not_leave_MDC_on_subscriber_thread() {
        Mono<String> failed = Mono.deferContextual(ignored -> {
                    assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isEqualTo("billing");
                    return Mono.<String>error(new IllegalStateException("boom"));
                })
                .contextWrite(RemoteServiceReactorContext.contextWrite("billing"));

        assertThatThrownBy(failed::block).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
    }

    @Test
    void cancelSignal_does_not_leave_MDC_on_subscriber_thread() {
        Mono<String> pending = Mono.deferContextual(ignored -> {
                    assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isEqualTo("billing");
                    return Mono.<String>never();
                })
                .contextWrite(RemoteServiceReactorContext.contextWrite("billing"));

        Disposable subscription = pending.subscribe();
        subscription.dispose();

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
    }
}
