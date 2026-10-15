package space.br1440.platform.tracing.runtime.propagation.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
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
        // when/then
        assertThat(propagation.resolve(null, controlOf(true, true))).isEqualTo(OutboundPropagationHeaders.EMPTY);
    }

    @Test
    void denyAllResolvesEmpty() {
        // when/then
        assertThat(propagation.resolve(OutboundPropagationDecision.DENY_ALL, controlOf(true, true)))
                .isEqualTo(OutboundPropagationHeaders.EMPTY);
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
                Optional.of(new OutboundPropagationHeaders.Header("X-Request-Id", "req-123")),
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
        assertThat(headers.requestId()).contains(new OutboundPropagationHeaders.Header("X-Request-Id", "req-123"));
        assertThat(headers.correlationId()).contains(new OutboundPropagationHeaders.Header(
                PlatformHeaders.X_CORRELATION_ID, "corr-123"));
    }

    @Test
    void allowAllResolvesControlHeadersAndApplicationRequestId() {
        // when
        OutboundPropagationHeaders headers = applicationPropagation.resolve(
                OutboundPropagationDecision.ALLOW_ALL, controlOf(true, true));

        // then
        assertThat(headers.forceTrace()).contains(new OutboundPropagationHeaders.Header("X-Trace-On", "on"));
        assertThat(headers.qaTrace()).contains(new OutboundPropagationHeaders.Header("X-QA-Trace", "1"));
        assertThat(headers.requestId()).contains(new OutboundPropagationHeaders.Header("X-Request-Id", "req-123"));
        assertThat(headers.correlationId()).contains(new OutboundPropagationHeaders.Header(
                PlatformHeaders.X_CORRELATION_ID, "corr-123"));
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
