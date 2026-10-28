package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.annotation.Nullable;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.internals.ProducerInterceptors;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class PlatformKafkaProducerInterceptorControlHeadersTest {

    private static final String FAILURE_MESSAGE = "outbound-secret-message";
    private static final String WARN_MESSAGE = "Failed to prepare platform propagation headers: java.lang.IllegalStateException";
    private static final OutboundPropagationHeaders ALL_HEADERS = new OutboundPropagationHeaders(
            Optional.of("on"), Optional.of("1"), Optional.of("req-1"), Optional.of("order-42")
    );
    private static final OutboundPropagationHeaders PARTIAL_HEADERS = new OutboundPropagationHeaders(
            Optional.empty(), Optional.of("1"), Optional.of("req-1"), Optional.of("order-42")
    );

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureWarnings() {
        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger(PlatformKafkaProducerInterceptor.class);
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseWarnings() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void carrierContainsOnlyPortSlotsAfterPolicyAndPropagation(boolean preset, Scenario scenario) {
        // given
        OutboundPropagationDecision decision = switch (scenario) {
            case PARTIAL_DENY -> new OutboundPropagationDecision(false, true, true, true);
            case DENY -> OutboundPropagationDecision.DENY_ALL;
            default -> OutboundPropagationDecision.ALLOW_ALL;
        };
        OutboundPropagationHeaders resolved = switch (scenario) {
            case ALLOW -> ALL_HEADERS;
            case PARTIAL_DENY -> PARTIAL_HEADERS;
            default -> OutboundPropagationHeaders.EMPTY;
        };
        OutboundPropagationPolicy policy = mock(OutboundPropagationPolicy.class);
        PlatformOutboundPropagation propagation = mock(PlatformOutboundPropagation.class);
        ProducerRecord<String, String> record = record(preset);
        when(policy.decide("orders")).thenAnswer(invocation -> {
            assertHeaders(record, OutboundPropagationHeaders.EMPTY);
            if (scenario == Scenario.POLICY_FAILURE) {
                throw new IllegalStateException(FAILURE_MESSAGE);
            }
            return decision;
        });
        when(propagation.resolve(any(), any())).thenAnswer(invocation -> {
            assertHeaders(record, OutboundPropagationHeaders.EMPTY);
            if (scenario == Scenario.RESOLVE_FAILURE) {
                throw new IllegalStateException(FAILURE_MESSAGE);
            }
            return resolved;
        });

        try (PlatformKafkaProducerInterceptor<String, String> interceptor = interceptorWith(policy, propagation)) {
            // when
            ProducerRecord<String, String> result = interceptor.onSend(record);

            // then
            assertThat(result).isSameAs(record);
            assertHeaders(record, resolved);
            verify(policy).decide("orders");
            if (scenario == Scenario.POLICY_FAILURE) {
                verifyNoInteractions(propagation);
            } else {
                verify(propagation).resolve(any(), any());
            }
            assertThat(appender.list).hasSize(
                    scenario == Scenario.POLICY_FAILURE || scenario == Scenario.RESOLVE_FAILURE ? 1 : 0
            );
        }
    }

    @Test
    void producerInterceptorChainReplacesCallerPresetsAndInvokesEachInterceptorOnce() {
        // given
        ProducerRecord<String, String> record = record(true);
        @SuppressWarnings("unchecked")
        ProducerInterceptor<String, String> caller = mock(ProducerInterceptor.class);
        when(caller.onSend(record)).thenAnswer(invocation -> {
            record.headers().add("x-request-id", bytes("caller-chain-value"));
            return record;
        });
        OutboundPropagationPolicy policy = mock(OutboundPropagationPolicy.class);
        PlatformOutboundPropagation propagation = mock(PlatformOutboundPropagation.class);
        when(policy.decide("orders")).thenReturn(OutboundPropagationDecision.ALLOW_ALL);
        when(propagation.resolve(any(), any())).thenReturn(ALL_HEADERS);
        PlatformKafkaProducerInterceptor<String, String> platform = interceptorWith(policy, propagation);

        try (ProducerInterceptors<String, String> chain = new ProducerInterceptors<>(List.of(caller, platform))) {
            // when
            ProducerRecord<String, String> result = chain.onSend(record);

            // then
            assertThat(result).isSameAs(record);
            assertHeaders(result, ALL_HEADERS);
            verify(caller).onSend(record);
            verify(policy).decide("orders");
            verify(propagation).resolve(any(), any());
        }
    }

    @Test
    void readOnlyHeadersRetainCallerValuesAndWarnOnceWithoutComputingPropagation() {
        // given
        ProducerRecord<String, String> record = record(true);
        Header[] original = record.headers().toArray();
        ((RecordHeaders) record.headers()).setReadOnly();
        OutboundPropagationPolicy policy = mock(OutboundPropagationPolicy.class);
        PlatformOutboundPropagation propagation = mock(PlatformOutboundPropagation.class);
        try (PlatformKafkaProducerInterceptor<String, String> interceptor = interceptorWith(policy, propagation)) {
            // when
            ProducerRecord<String, String> first = interceptor.onSend(record);
            ProducerRecord<String, String> repeated = interceptor.onSend(record);

            // then
            assertThat(first).isSameAs(record);
            assertThat(repeated).isSameAs(record);
            assertThat(record.headers().toArray()).containsExactly(original);
            verifyNoInteractions(policy, propagation);
            assertWarnings(1);
        }
    }

    @Test
    void propagationFailureWarnIsBoundedPerInstanceWithoutLeakingExceptionDetails() {
        // given
        OutboundPropagationPolicy policy = mock(OutboundPropagationPolicy.class);
        PlatformOutboundPropagation propagation = mock(PlatformOutboundPropagation.class);
        when(policy.decide("orders")).thenReturn(OutboundPropagationDecision.ALLOW_ALL);
        when(propagation.resolve(any(), any())).thenThrow(new IllegalStateException(FAILURE_MESSAGE));
        ProducerRecord<String, String> firstRecord = record(true);
        ProducerRecord<String, String> repeatedRecord = record(true);
        ProducerRecord<String, String> secondRecord = record(true);

        try (PlatformKafkaProducerInterceptor<String, String> first = interceptorWith(policy, propagation);
             PlatformKafkaProducerInterceptor<String, String> second = interceptorWith(policy, propagation)) {
            // when
            ProducerRecord<String, String> firstResult = first.onSend(firstRecord);
            ProducerRecord<String, String> repeatedResult = first.onSend(repeatedRecord);

            // then
            assertThat(firstResult).isSameAs(firstRecord);
            assertThat(repeatedResult).isSameAs(repeatedRecord);
            assertWarnings(1);

            // when
            ProducerRecord<String, String> secondResult = second.onSend(secondRecord);

            // then
            assertThat(secondResult).isSameAs(secondRecord);
            assertWarnings(2);
            assertHeaders(firstRecord, OutboundPropagationHeaders.EMPTY);
            assertHeaders(repeatedRecord, OutboundPropagationHeaders.EMPTY);
            assertHeaders(secondRecord, OutboundPropagationHeaders.EMPTY);
            verify(policy, times(3)).decide("orders");
            verify(propagation, times(3)).resolve(any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            PlatformKafkaProducerInterceptor.CONFIG_POLICY,
            PlatformKafkaProducerInterceptor.CONFIG_PROPAGATION,
            PlatformKafkaProducerInterceptor.CONFIG_IDENTITY
    })
    void configureRejectsEachMissingDependency(String key) {
        // given
        Map<String, Object> configuration = configuration(
                destination -> OutboundPropagationDecision.ALLOW_ALL,
                (decision, control) -> OutboundPropagationHeaders.EMPTY
        );
        configuration.remove(key);
        try (PlatformKafkaProducerInterceptor<String, String> interceptor = new PlatformKafkaProducerInterceptor<>()) {
            // when
            // then
            assertThatThrownBy(() -> interceptor.configure(configuration))
                    .isInstanceOf(ConfigException.class)
                    .hasMessage("Missing or invalid configuration: " + key);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            PlatformKafkaProducerInterceptor.CONFIG_POLICY,
            PlatformKafkaProducerInterceptor.CONFIG_PROPAGATION,
            PlatformKafkaProducerInterceptor.CONFIG_IDENTITY
    })
    void configureRejectsEachInvalidDependencyWithoutExposingItsValue(String key) {
        // given
        Map<String, Object> configuration = configuration(
                destination -> OutboundPropagationDecision.ALLOW_ALL,
                (decision, control) -> OutboundPropagationHeaders.EMPTY
        );
        configuration.put(key, FAILURE_MESSAGE);
        try (PlatformKafkaProducerInterceptor<String, String> interceptor = new PlatformKafkaProducerInterceptor<>()) {
            // when
            // then
            assertThatThrownBy(() -> interceptor.configure(configuration))
                    .isInstanceOf(ConfigException.class)
                    .hasMessage("Missing or invalid configuration: " + key);
        }
    }

    private static Stream<Arguments> scenarios() {
        return Stream.of(false, true)
                .flatMap(preset -> Stream.of(Scenario.values()).map(scenario -> Arguments.of(preset, scenario)));
    }

    private static ProducerRecord<String, String> record(boolean preset) {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key", "value");
        if (preset) {
            for (String name : PlatformHeaders.OUTBOUND_CONTROL_HEADERS) {
                record.headers().add(name, bytes("caller-first"));
                record.headers().add(name.toLowerCase(Locale.ROOT), bytes("caller-lowercase"));
                record.headers().add(name.toUpperCase(Locale.ROOT), bytes("caller-uppercase"));
                record.headers().add(name, bytes("caller-duplicate"));
            }
        }
        record.headers().add("X-Unrelated", bytes("keep-first"));
        record.headers().add("x-unrelated", bytes("keep-second"));
        return record;
    }

    private static void assertHeaders(ProducerRecord<String, String> record, OutboundPropagationHeaders expected) {
        assertSlot(record, PlatformHeaders.X_TRACE_ON, expected.forceTrace().orElse(null));
        assertSlot(record, PlatformHeaders.X_QA_TRACE, expected.qaTrace().orElse(null));
        assertSlot(record, PlatformHeaders.X_REQUEST_ID, expected.requestId().orElse(null));
        assertSlot(record, PlatformHeaders.X_CORRELATION_ID, expected.correlationId().orElse(null));
        assertThat(record.headers().headers("X-Unrelated")).singleElement()
                .satisfies(header -> assertThat(header.value()).isEqualTo(bytes("keep-first")));
        assertThat(record.headers().headers("x-unrelated")).singleElement()
                .satisfies(header -> assertThat(header.value()).isEqualTo(bytes("keep-second")));
    }

    private static void assertSlot(ProducerRecord<String, String> record, String name, @Nullable String value) {
        List<Header> matching = Stream.of(record.headers().toArray())
                .filter(header -> name.equalsIgnoreCase(header.key()))
                .toList();
        if (value == null) {
            assertThat(matching).isEmpty();
        } else {
            assertThat(matching).singleElement().satisfies(header -> {
                assertThat(header.key()).isEqualTo(name);
                assertThat(header.value()).isEqualTo(bytes(value));
            });
        }
    }

    private void assertWarnings(int count) {
        assertThat(appender.list).hasSize(count).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(WARN_MESSAGE);
            assertThat(event.getFormattedMessage()).doesNotContain(FAILURE_MESSAGE);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static PlatformKafkaProducerInterceptor<String, String> interceptorWith(OutboundPropagationPolicy policy,
                                                                                    PlatformOutboundPropagation propagation) {
        PlatformKafkaProducerInterceptor<String, String> interceptor = new PlatformKafkaProducerInterceptor<>();
        interceptor.configure(configuration(policy, propagation));
        return interceptor;
    }

    private static Map<String, Object> configuration(OutboundPropagationPolicy policy,
                                                     PlatformOutboundPropagation propagation) {
        return new HashMap<>(Map.of(
                PlatformKafkaProducerInterceptor.CONFIG_POLICY, policy,
                PlatformKafkaProducerInterceptor.CONFIG_PROPAGATION, propagation,
                PlatformKafkaProducerInterceptor.CONFIG_IDENTITY,
                new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop())
        ));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private enum Scenario {
        ALLOW, PARTIAL_DENY, DENY, EMPTY, POLICY_FAILURE, RESOLVE_FAILURE
    }
}
