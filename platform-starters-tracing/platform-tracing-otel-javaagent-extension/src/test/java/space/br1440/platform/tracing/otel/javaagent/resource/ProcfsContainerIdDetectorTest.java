package space.br1440.platform.tracing.otel.javaagent.resource;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProcfsContainerIdDetectorTest {

    private static final String CONTAINER_ID_64 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void parse_docker_full_64_hex() {
        // given
        String cgroup = "12:memory:/docker/" + CONTAINER_ID_64 + "/ab";

        // when
        Optional<String> id = ProcfsContainerIdDetector.parse(cgroup);

        // then
        assertThat(id).contains(CONTAINER_ID_64);
    }

    @Test
    void parse_containerd_cri_scope() {
        // given
        String cgroup = "0::/cri-containerd-" + CONTAINER_ID_64 + ".scope";

        // when
        Optional<String> id = ProcfsContainerIdDetector.parse(cgroup);

        // then
        assertThat(id).contains(CONTAINER_ID_64);
    }

    @Test
    void parse_docker_short_12_hex() {
        // given
        String cgroup = "12:memory:/docker/deadbeefcafe/name";

        // when
        Optional<String> id = ProcfsContainerIdDetector.parse(cgroup);

        // then
        assertThat(id).contains("deadbeefcafe");
    }

    @Test
    void parse_returns_empty_for_unrecognized_cgroup() {
        // given/when/then
        assertThat(ProcfsContainerIdDetector.parse("12:memory:/user.slice/user-1000.slice/session-1.scope"))
                .isEmpty();
    }

    @Test
    void detect_uses_injected_cgroup_reader() {
        // given
        ProcfsContainerIdDetector.CgroupFileReader reader = path -> Optional.of(
                "0::/cri-containerd-" + CONTAINER_ID_64 + ".scope"
        );
        ProcfsContainerIdDetector detector = new ProcfsContainerIdDetector(reader);

        // when/then
        assertThat(detector.detect()).contains(CONTAINER_ID_64);
    }
}
