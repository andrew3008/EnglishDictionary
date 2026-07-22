package space.br1440.platform.tracing.otel.propagation.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultOutboundPropagationPolicy: trusted gating + per-header decision")
class DefaultOutboundPropagationPolicyTest {

    private static OutboundPropagationPolicy policy(boolean enabled, boolean force, boolean qa, boolean reqId) {
        TrustedDestinationMatcher matcher = TrustedDestinationMatchers.forHttpHosts(List.of("*.trusted.com"), false);
        return new DefaultOutboundPropagationPolicy(enabled, matcher, force, qa, reqId);
    }

    @Test
    @DisplayName("outbound выключен -> DENY_ALL даже для доверенного хоста")
    void disabledReturnsDenyAll() {
        assertThat(policy(false, true, true, true).decide("a.trusted.com"))
                .isEqualTo(OutboundPropagationDecision.DENY_ALL);
    }

    @Test
    @DisplayName("недоверенный хост -> DENY_ALL")
    void untrustedReturnsDenyAll() {
        assertThat(policy(true, true, true, true).decide("evil.com"))
                .isEqualTo(OutboundPropagationDecision.DENY_ALL);
    }

    @Test
    @DisplayName("доверенный хост -> per-header флаги")
    void trustedAppliesPerHeaderFlags() {
        OutboundPropagationDecision d = policy(true, false, false, true).decide("a.trusted.com");
        assertThat(d.propagateRequestId()).isTrue();
        assertThat(d.propagateForceTrace()).isFalse();
        assertThat(d.propagateQaTrace()).isFalse();
    }

    @Test
    @DisplayName("null host -> DENY_ALL")
    void nullHostDenied() {
        assertThat(policy(true, true, true, true).decide(null))
                .isEqualTo(OutboundPropagationDecision.DENY_ALL);
    }
}
