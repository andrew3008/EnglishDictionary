package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IpPrefixTruncatorTest {

    @Test
    void ipv4_is_truncated_to_24() {
        // given/when/then
        assertThat(IpPrefixTruncator.truncate("192.168.12.34")).isEqualTo("192.168.12.0");
    }

    @Test
    void ipv4_with_port_is_truncated_without_failure() {
        // given/when/then
        assertThat(IpPrefixTruncator.truncate("10.1.2.3:8080")).isEqualTo("10.1.2.0");
    }

    @Test
    void x_forwarded_for_processes_each_element() {
        // when
        String result = IpPrefixTruncator.truncate("1.2.3.4, 10.0.0.1");
        // then
        assertThat(result).isEqualTo("1.2.3.0, 10.0.0.0");
    }

    @Test
    void x_forwarded_for_with_invalid_element_is_rejected_entirely() {
        // when/then
        assertThat(IpPrefixTruncator.truncate("1.2.3.4, attacker.example, 10.0.0.1")).isNull();
        assertThat(IpPrefixTruncator.truncate("1.2.3.4, ")).isNull();
    }

    @Test
    void ipv6_is_truncated_to_64() {
        // given/when/then
        assertThat(IpPrefixTruncator.truncate("2001:db8:3333:4444:5555:6666:7777:8888"))
                .isEqualTo("2001:db8:3333:4444::");
    }

    @Test
    void compressed_ipv6_is_truncated_by_real_eight_groups() {
        // when/then
        assertThat(IpPrefixTruncator.truncate("2001:db8::1"))
                .isEqualTo("2001:db8:0:0::");
        assertThat(IpPrefixTruncator.truncate("::1"))
                .isEqualTo("0:0:0:0::");
        assertThat(IpPrefixTruncator.truncate("[2001:db8:1:2::99]:443"))
                .isEqualTo("2001:db8:1:2::");
    }

    @Test
    void ipv4_mapped_ipv6_reduces_to_ipv4() {
        // given/when/then
        assertThat(IpPrefixTruncator.truncate("::ffff:192.168.1.1")).isEqualTo("192.168.1.0");
    }

    @Test
    void invalid_ip_returns_null_without_exception() {
        // when/then
        assertThat(IpPrefixTruncator.truncate("not-an-ip")).isNull();
        assertThat(IpPrefixTruncator.truncate("999.999.999.999")).isNull();
        assertThat(IpPrefixTruncator.truncate("")).isNull();
        assertThat(IpPrefixTruncator.truncate(null)).isNull();
    }
}
