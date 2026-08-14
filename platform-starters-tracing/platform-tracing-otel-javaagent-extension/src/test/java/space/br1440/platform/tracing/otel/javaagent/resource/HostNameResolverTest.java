package space.br1440.platform.tracing.otel.javaagent.resource;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HostNameResolverTest {

    @Test
    void hostname_из_env_HOSTNAME_lowercase() {
        HostNameResolver resolver = new HostNameResolver(
                name -> "HOSTNAME".equals(name) ? "Pod-ABC" : null,
                () -> {
                    throw new AssertionError("InetAddress не должен вызываться при наличии HOSTNAME");
                });

        assertThat(resolver.resolve()).contains("pod-abc");
    }

    @Test
    void computername_fallback_на_windows() {
        HostNameResolver resolver = new HostNameResolver(
                name -> "COMPUTERNAME".equals(name) ? "DEV-BOX" : null,
                () -> null);

        assertThat(resolver.resolve()).contains("dev-box");
    }

    @Test
    void inet_fallback_когда_env_пуст() {
        HostNameResolver resolver = new HostNameResolver(name -> null, () -> "Inet-Host");

        assertThat(resolver.resolve()).contains("inet-host");
    }

    @Test
    void empty_когда_ничего_не_определено() {
        HostNameResolver resolver = new HostNameResolver(name -> null, () -> null);

        assertThat(resolver.resolve()).isEqualTo(Optional.empty());
    }
}
