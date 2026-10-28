package space.br1440.platform.tracing.runtime.propagation.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

class DefaultTraceControlHeaderInjectorTest {

    private final PlatformOutboundPropagation propagation = new DefaultTraceControlHeaderInjector(
                    OutboundApplicationIdentitySource.of(() -> null, () -> null)
            );

    private final PlatformOutboundPropagation applicationPropagation = new DefaultTraceControlHeaderInjector(
            new FixedApplicationIdentitySource("req-123", "corr-123")
    );

    @SuppressWarnings("SameParameterValue")
    private static InboundTraceControl controlOf(boolean forceTrace, boolean qaTrace) {
        return new InboundTraceControl(forceTrace, qaTrace, "req-from-control", "some-reason", "raw-value");
    }

    @Test
    void noDecisionResolvesEmpty() {
        // given
        CountingApplicationIdentitySource identitySource = new CountingApplicationIdentitySource();
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(identitySource);

        // when
        OutboundPropagationHeaders headers = resolver.resolve(null, controlOf(true, true));

        // then
        assertThat(headers).isEqualTo(OutboundPropagationHeaders.EMPTY);
        assertThat(identitySource.requestIdReads).isZero();
        assertThat(identitySource.correlationIdReads).isZero();
    }

    @Test
    void denyAllResolvesEmpty() {
        // given
        CountingApplicationIdentitySource identitySource = new CountingApplicationIdentitySource();
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(identitySource);

        // when
        OutboundPropagationHeaders headers = resolver.resolve(OutboundPropagationDecision.DENY_ALL, controlOf(true, true));

        // then
        assertThat(headers).isEqualTo(OutboundPropagationHeaders.EMPTY);
        assertThat(identitySource.requestIdReads).isZero();
        assertThat(identitySource.correlationIdReads).isZero();
    }

    @Test
    void requestIdComesFromApplicationIdentityNotControl() {
        // when
        OutboundPropagationHeaders headers = applicationPropagation.resolve(
                new OutboundPropagationDecision(false, false, true, false), controlOf(true, true));

        // then
        assertThat(headers).isEqualTo(new OutboundPropagationHeaders(
                Optional.empty(),
                Optional.empty(),
                Optional.of("req-123"),
                Optional.empty()));
    }

    @Test
    void requestIdIsAbsentWhenApplicationIdentityIsEmptyEvenIfControlHasValue() {
        // when
        OutboundPropagationHeaders headers = propagation.resolve(
                new OutboundPropagationDecision(false, false, true, false), controlOf(true, true));

        // then
        assertThat(headers).isEqualTo(OutboundPropagationHeaders.EMPTY);
    }

    @Test
    void forceAndQaSkippedWhenDecisionFalse() {
        // when
        OutboundPropagationHeaders headers = applicationPropagation.resolve(
                new OutboundPropagationDecision(false, false, false, false), controlOf(true, true));

        // then
        assertThat(headers).isEqualTo(OutboundPropagationHeaders.EMPTY);
    }

    @Test
    void forceAndQaSkippedWhenControlIsNullOrFalse() {
        // when
        OutboundPropagationHeaders headers = applicationPropagation.resolve(
                OutboundPropagationDecision.ALLOW_ALL, null);

        // then
        assertThat(headers.forceTrace()).isEmpty();
        assertThat(headers.qaTrace()).isEmpty();
        assertThat(headers.requestId()).contains("req-123");
        assertThat(headers.correlationId()).contains("corr-123");
    }

    @Test
    void allowAllResolvesControlHeadersAndApplicationRequestId() {
        // when
        OutboundPropagationHeaders headers = applicationPropagation.resolve(
                OutboundPropagationDecision.ALLOW_ALL, controlOf(true, true));

        // then
        assertThat(headers.forceTrace()).contains("on");
        assertThat(headers.qaTrace()).contains("1");
        assertThat(headers.requestId()).contains("req-123");
        assertThat(headers.correlationId()).contains("corr-123");
    }

    @Test
    void correlationIdSkippedWhenDecisionFalseEvenIfApplicationIdentityHasValue() {
        // when
        OutboundPropagationHeaders headers = applicationPropagation.resolve(
                new OutboundPropagationDecision(false, false, false, false), controlOf(true, true));

        // then
        assertThat(headers.correlationId()).isEmpty();
    }

    @Test
    void samplingReasonAndRawForceTraceValueDoNotAffectResolvedHeaders() {
        // given
        InboundTraceControl withOneReason = new InboundTraceControl(true, true, "req-from-control", "force", "on");
        InboundTraceControl withDifferentReason = new InboundTraceControl(true, true, "req-from-control", "qa", "1");

        // when
        OutboundPropagationHeaders first = applicationPropagation.resolve(OutboundPropagationDecision.ALLOW_ALL, withOneReason);
        OutboundPropagationHeaders second = applicationPropagation.resolve(OutboundPropagationDecision.ALLOW_ALL, withDifferentReason);

        // then
        assertThat(first).isEqualTo(second);
    }

    @Test
    void forceAndQaOnlyDoNotReadApplicationIdentity() {
        // given
        CountingApplicationIdentitySource identitySource = new CountingApplicationIdentitySource();
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(identitySource);

        // when
        OutboundPropagationHeaders headers = resolver.resolve(
                new OutboundPropagationDecision(true, true, false, false), controlOf(true, true));

        // then
        assertThat(headers).isEqualTo(new OutboundPropagationHeaders(
                Optional.of("on"), Optional.of("1"), Optional.empty(), Optional.empty()));
        assertThat(identitySource.requestIdReads).isZero();
        assertThat(identitySource.correlationIdReads).isZero();
    }

    @Test
    void requestIdOnlyDoesNotReadCorrelationId() {
        // given
        CountingApplicationIdentitySource identitySource = new CountingApplicationIdentitySource();
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(identitySource);

        // when
        OutboundPropagationHeaders headers = resolver.resolve(
                new OutboundPropagationDecision(false, false, true, false), controlOf(true, true));

        // then
        assertThat(headers.requestId()).contains("req-123");
        assertThat(headers.correlationId()).isEmpty();
        assertThat(identitySource.requestIdReads).isEqualTo(1);
        assertThat(identitySource.correlationIdReads).isZero();
    }

    @Test
    void correlationIdOnlyDoesNotReadRequestId() {
        // given
        CountingApplicationIdentitySource identitySource = new CountingApplicationIdentitySource();
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(identitySource);

        // when
        OutboundPropagationHeaders headers = resolver.resolve(
                new OutboundPropagationDecision(false, false, false, true), controlOf(true, true));

        // then
        assertThat(headers.requestId()).isEmpty();
        assertThat(headers.correlationId()).contains("corr-123");
        assertThat(identitySource.requestIdReads).isZero();
        assertThat(identitySource.correlationIdReads).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void applicationIdentityFailurePropagatesToAdapter(boolean requestId) {
        // given
        IllegalStateException failure = new IllegalStateException("Identity lookup failed");
        OutboundApplicationIdentitySource identitySource = OutboundApplicationIdentitySource.of(
                () -> {
                    throw failure;
                },
                () -> {
                    throw failure;
                });
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(identitySource);
        OutboundPropagationDecision decision = new OutboundPropagationDecision(true, true, requestId, !requestId);

        // when
        // then
        assertThatThrownBy(() -> resolver.resolve(decision, controlOf(true, true))).isSameAs(failure);
    }

    @ParameterizedTest
    @MethodSource("invalidIdentityValues")
    void noncanonicalRequestIdDoesNotSuppressOtherSlots(String invalid) {
        // given
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(
                new FixedApplicationIdentitySource(invalid, "corr-123"));

        // when
        OutboundPropagationHeaders headers = resolver.resolve(OutboundPropagationDecision.ALLOW_ALL, controlOf(true, true));

        // then
        assertThat(headers).isEqualTo(new OutboundPropagationHeaders(
                Optional.of("on"), Optional.of("1"), Optional.empty(), Optional.of("corr-123")));
    }

    @ParameterizedTest
    @MethodSource("invalidIdentityValues")
    void noncanonicalCorrelationIdDoesNotSuppressOtherSlots(String invalid) {
        // given
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(
                new FixedApplicationIdentitySource("req-123", invalid));

        // when
        OutboundPropagationHeaders headers = resolver.resolve(OutboundPropagationDecision.ALLOW_ALL, controlOf(true, true));

        // then
        assertThat(headers).isEqualTo(new OutboundPropagationHeaders(
                Optional.of("on"), Optional.of("1"), Optional.of("req-123"), Optional.empty()));
    }

    @Test
    void canonicalIdentityAtMaximumLengthIsPreserved() {
        // given
        String requestId = "a".repeat(128);
        String correlationId = "b".repeat(128);
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(
                new FixedApplicationIdentitySource(requestId, correlationId));

        // when
        OutboundPropagationHeaders headers = resolver.resolve(OutboundPropagationDecision.ALLOW_ALL, null);

        // then
        assertThat(headers.requestId()).contains(requestId);
        assertThat(headers.correlationId()).contains(correlationId);
    }

    @Test
    void paddedIdentityUsesCanonicalSanitizerResult() {
        // given
        PlatformOutboundPropagation resolver = new DefaultTraceControlHeaderInjector(
                new FixedApplicationIdentitySource(" req-123 ", " corr-123 "));

        // when
        OutboundPropagationHeaders headers = resolver.resolve(OutboundPropagationDecision.ALLOW_ALL, null);

        // then
        assertThat(headers.requestId()).contains("req-123");
        assertThat(headers.correlationId()).contains("corr-123");
    }

    private static Stream<String> invalidIdentityValues() {
        return Stream.of(null, "", " ", "a b", "a\r\nb", "a".repeat(129));
    }

    private static final class CountingApplicationIdentitySource implements OutboundApplicationIdentitySource {

        private int requestIdReads;
        private int correlationIdReads;

        @Override
        public String currentRequestId() {
            requestIdReads++;
            return "req-123";
        }

        @Override
        public String currentCorrelationId() {
            correlationIdReads++;
            return "corr-123";
        }
    }

    private record FixedApplicationIdentitySource(String requestId, String correlationId)
            implements OutboundApplicationIdentitySource {

        @Override
        public String currentRequestId() {
            return requestId;
        }

        @Override
        public String currentCorrelationId() {
            return correlationId;
        }
    }
}
