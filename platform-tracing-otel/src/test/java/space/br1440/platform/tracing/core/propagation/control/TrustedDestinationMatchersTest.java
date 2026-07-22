package space.br1440.platform.tracing.core.propagation.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TrustedDestinationMatchers: host hardening и label-aware glob")
class TrustedDestinationMatchersTest {

    @Test
    @DisplayName("точное совпадение хоста")
    void exactHostMatch() {
        TrustedDestinationMatcher m = TrustedDestinationMatchers.forHttpHosts(List.of("api.internal"), false);
        assertThat(m.isTrusted("api.internal")).isTrue();
        assertThat(m.isTrusted("API.INTERNAL")).isTrue();
        assertThat(m.isTrusted("api.internal.")).isTrue();
    }

    @Test
    @DisplayName("* соответствует ровно одному label'у, не нескольким")
    void singleLabelWildcard() {
        TrustedDestinationMatcher m = TrustedDestinationMatchers.forHttpHosts(List.of("*.trusted.com"), false);
        assertThat(m.isTrusted("a.trusted.com")).isTrue();
        assertThat(m.isTrusted("a.b.trusted.com")).isFalse();
    }

    @Test
    @DisplayName("** соответствует одному и более label'ам")
    void multiLabelWildcard() {
        TrustedDestinationMatcher m = TrustedDestinationMatchers.forHttpHosts(List.of("**.trusted.com"), false);
        assertThat(m.isTrusted("a.trusted.com")).isTrue();
        assertThat(m.isTrusted("a.b.trusted.com")).isTrue();
    }

    @Test
    @DisplayName("обходные кейсы allowlist отклоняются (DENY)")
    void bypassAttemptsDenied() {
        TrustedDestinationMatcher m = TrustedDestinationMatchers.forHttpHosts(
                List.of("trusted.com", "*.trusted.com"), false);
        assertThat(m.isTrusted("trusted.com.evil.com")).isFalse();
        assertThat(m.isTrusted("eviltrusted.com")).isFalse();
        assertThat(m.isTrusted("trusted.com:8443")).isFalse();
        assertThat(m.isTrusted("trusted.com@evil.com")).isFalse();
        assertThat(m.isTrusted("trusted.com%00.evil.com")).isFalse();
        assertThat(m.isTrusted("trusted.com\r\nHost: evil")).isFalse();
    }

    @Test
    @DisplayName("IP-литералы запрещены по умолчанию")
    void ipLiteralsDeniedByDefault() {
        TrustedDestinationMatcher m = TrustedDestinationMatchers.forHttpHosts(
                List.of("*", "**", "127.0.0.1"), false);
        assertThat(m.isTrusted("127.0.0.1")).isFalse();
        assertThat(m.isTrusted("[::1]")).isFalse();
    }

    @Test
    @DisplayName("null/blank host -> DENY")
    void nullOrBlankDenied() {
        TrustedDestinationMatcher m = TrustedDestinationMatchers.forHttpHosts(List.of("*.trusted.com"), false);
        assertThat(m.isTrusted(null)).isFalse();
        assertThat(m.isTrusted("  ")).isFalse();
    }

    @Test
    @DisplayName("Kafka-топики: case-sensitive, * — любая последовательность")
    void kafkaTopics() {
        TrustedDestinationMatcher m = TrustedDestinationMatchers.forKafkaTopics(List.of("orders.*", "billing_events"));
        assertThat(m.isTrusted("orders.v1")).isTrue();
        assertThat(m.isTrusted("orders.v1.dlq")).isTrue();
        assertThat(m.isTrusted("billing_events")).isTrue();
        assertThat(m.isTrusted("Orders.v1")).isFalse();
        assertThat(m.isTrusted("payments")).isFalse();
    }
}
