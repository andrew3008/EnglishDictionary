package space.br1440.platform.tracing.otel.javaagent.resource;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HostNameResolverTest {

    @Test
    void hostname_from_env_HOSTNAME_lowercase() {
        HostNameResolver resolver = new HostNameResolver(
                name -> "HOSTNAME".equals(name) ? "Pod-ABC" : null,
                () -> {
                    throw new AssertionError("InetAddress must not be called when HOSTNAME is present");
                });

        assertThat(resolver.resolve()).contains("pod-abc");
    }

    @Test
    void computername_fallback_on_windows() {
        HostNameResolver resolver = new HostNameResolver(
                name -> "COMPUTERNAME".equals(name) ? "DEV-BOX" : null,
                () -> null);

        assertThat(resolver.resolve()).contains("dev-box");
    }

    @Test
    void inet_fallback_when_env_empty() {
        HostNameResolver resolver = new HostNameResolver(name -> null, () -> "Inet-Host");

        assertThat(resolver.resolve()).contains("inet-host");
    }

    @Test
    void empty_when_nothing_defined() {
        HostNameResolver resolver = new HostNameResolver(name -> null, () -> null);

        assertThat(resolver.resolve()).isEqualTo(Optional.empty());
    }
}
