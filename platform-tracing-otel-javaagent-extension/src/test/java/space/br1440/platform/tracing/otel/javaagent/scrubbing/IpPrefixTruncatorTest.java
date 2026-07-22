package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Краевые случаи усечения IP до подсети (IPv4 /24, IPv6 /64).
 */
class IpPrefixTruncatorTest {

    @Test
    void ipv4_усекается_до_24() {
        assertThat(IpPrefixTruncator.truncate("192.168.12.34")).isEqualTo("192.168.12.0");
    }

    @Test
    void ipv4_с_портом_усекается_без_падения() {
        assertThat(IpPrefixTruncator.truncate("10.1.2.3:8080")).isEqualTo("10.1.2.0");
    }

    @Test
    void x_forwarded_for_обрабатывает_каждый_элемент() {
        String result = IpPrefixTruncator.truncate("1.2.3.4, 10.0.0.1");
        assertThat(result).isEqualTo("1.2.3.0, 10.0.0.0");
    }

    @Test
    void ipv6_усекается_до_64() {
        assertThat(IpPrefixTruncator.truncate("2001:db8:3333:4444:5555:6666:7777:8888"))
                .isEqualTo("2001:db8:3333:4444::");
    }

    @Test
    void ipv4_mapped_ipv6_сводится_к_ipv4() {
        assertThat(IpPrefixTruncator.truncate("::ffff:192.168.1.1")).isEqualTo("192.168.1.0");
    }

    @Test
    void невалидный_ip_возвращает_null_без_исключения() {
        assertThat(IpPrefixTruncator.truncate("not-an-ip")).isNull();
        assertThat(IpPrefixTruncator.truncate("999.999.999.999")).isNull();
        assertThat(IpPrefixTruncator.truncate("")).isNull();
        assertThat(IpPrefixTruncator.truncate(null)).isNull();
    }
}
