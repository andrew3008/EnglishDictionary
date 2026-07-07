package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceEffectiveSnapshotTest {

    @Test
    void otel_service_name_из_env_имеет_приоритет() {
        Map<String, String> env = new HashMap<>();
        env.put("OTEL_SERVICE_NAME", "otel-svc");
        env.put("PLATFORM_TRACING_SERVICE_NAME", "platform-svc");

        Map<String, Map<String, Object>> snapshot =
                new ResourceEffectiveSnapshot(key -> null, env::get).build();

        assertThat(snapshot.get("service.name"))
                .containsEntry("source", "env-var")
                .containsEntry("sourceKey", "OTEL_SERVICE_NAME")
                .containsEntry("value", "otel-svc");
    }

    @Test
    void platform_env_когда_otel_пуст() {
        Map<String, String> env = Map.of("PLATFORM_TRACING_SERVICE_NAME", "platform-svc");

        Map<String, Map<String, Object>> snapshot =
                new ResourceEffectiveSnapshot(key -> null, env::get).build();

        assertThat(snapshot.get("service.name"))
                .containsEntry("source", "env-var")
                .containsEntry("value", "platform-svc");
    }

    @Test
    void version_absent_помечается_build_info_or_absent() {
        Map<String, Map<String, Object>> snapshot =
                new ResourceEffectiveSnapshot(key -> null, key -> null).build();

        assertThat(snapshot.get("service.version")).containsEntry("source", "build-info-or-absent");
        assertThat(snapshot.get("platform.c_group")).containsEntry("source", "absent");
    }

    @Test
    void system_property_имеет_приоритет_над_env() {
        Map<String, String> sys = Map.of("platform.tracing.service.environment", "production");
        Map<String, String> env = Map.of("PLATFORM_TRACING_SERVICE_ENVIRONMENT", "staging");

        Map<String, Map<String, Object>> snapshot =
                new ResourceEffectiveSnapshot(sys::get, env::get).build();

        assertThat(snapshot.get("deployment.environment.name"))
                .containsEntry("source", "system-property")
                .containsEntry("value", "production");
    }
}
