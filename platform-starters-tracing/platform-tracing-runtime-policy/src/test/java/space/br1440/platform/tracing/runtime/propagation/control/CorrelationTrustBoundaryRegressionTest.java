package space.br1440.platform.tracing.runtime.propagation.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;

@DisplayName("Correlation trust-boundary regression (trusted vs untrusted destination)")
class CorrelationTrustBoundaryRegressionTest {

    private static final String CORRELATION_ID = "trusted-correlation";

    @Test
    @DisplayName("trusted destination получает X-Correlation-Id, untrusted — нет, при одном и том же application identity")
    void trustedDestinationReceivesHeader_untrustedDoesNot() {
        // given
        TrustedDestinationMatcher matcher = TrustedDestinationMatchers.forHttpHosts(List.of("*.trusted.com"), false);
        OutboundPropagationPolicy policy = new DefaultOutboundPropagationPolicy(
                true, matcher, false, false, false, true
        );
        OutboundApplicationIdentitySource identitySource = OutboundApplicationIdentitySource.of(
                () -> null,
                () -> CORRELATION_ID
        );
        DefaultTraceControlHeaderInjector injector = new DefaultTraceControlHeaderInjector(identitySource);

        OutboundPropagationDecision trustedDecision = policy.decide("api.trusted.com");
        OutboundPropagationDecision untrustedDecision = policy.decide("evil.com");

        // when
        OutboundPropagationHeaders trustedHeaders = injector.resolve(trustedDecision, null);
        OutboundPropagationHeaders untrustedHeaders = injector.resolve(untrustedDecision, null);

        // then
        assertThat(trustedHeaders.correlationId()).contains(CORRELATION_ID);
        assertThat(untrustedHeaders.correlationId()).isEmpty();
    }
}
