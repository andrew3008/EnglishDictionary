package space.br1440.platform.tracing.otel.javaagent.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class HostNameResolverTest {

    private static final String SECRET_MARKER = "hostname-failure-secret";

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void hostname_from_env_HOSTNAME_lowercase() {
        // given
        HostNameResolver resolver = new HostNameResolver(
                name -> "HOSTNAME".equals(name) ? "Pod-ABC" : null,
                () -> {
                    throw new AssertionError("InetAddress must not be called when HOSTNAME is present");
                });

        // when/then
        assertThat(resolver.resolve()).contains("pod-abc");
    }

    @Test
    void computername_fallback_on_windows() {
        // given
        HostNameResolver resolver = new HostNameResolver(
                name -> "COMPUTERNAME".equals(name) ? "DEV-BOX" : null,
                () -> null);

        // when/then
        assertThat(resolver.resolve()).contains("dev-box");
    }

    @Test
    void inet_fallback_when_env_empty() {
        // given
        HostNameResolver resolver = new HostNameResolver(name -> null, () -> "Inet-Host");

        // when/then
        assertThat(resolver.resolve()).contains("inet-host");
    }

    @Test
    void empty_when_nothing_defined() {
        // given
        HostNameResolver resolver = new HostNameResolver(name -> null, () -> null);

        // when/then
        assertThat(resolver.resolve()).isEqualTo(Optional.empty());
    }

    @Test
    void lookup_failure_includes_type_without_arbitrary_message() {
        // given
        IllegalStateException failure = new IllegalStateException(SECRET_MARKER);

        // when
        String hostname = HostNameResolver.resolveLocalHostNameWithTimeout(() -> {
            throw failure;
        });

        // then
        assertThat(hostname).isNull();
        assertThat(logs.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.BOOT_HOSTNAME_FAILED)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.fields())
                            .containsOnlyKeys("failureType")
                            .containsEntry("failureType", IllegalStateException.class.getName());
                    assertThat(event.throwableDigest())
                            .contains(IllegalStateException.class.getName())
                            .doesNotContain(SECRET_MARKER);
                });
        assertThat(logs.rendered()).doesNotContain(SECRET_MARKER);
    }
}
