package space.br1440.platform.tracing.otel.javaagent.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BuildInfoReaderTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    private static Function<String, InputStream> loaderOf(Map<String, String> contentByPath) {
        return path -> {
            String content = contentByPath.get(path);
            return content == null ? null : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        };
    }

    @Test
    void readsBuildVersionFromRoot() {
        // given
        Function<String, InputStream> loader = loaderOf(Map.of(
                "META-INF/build-info.properties",
                "build.version=2.17.4\nbuild.name=demo\n"
        ));

        // when/then
        assertThat(new BuildInfoReader(loader).readVersion()).contains("2.17.4");
    }

    @Test
    void fallbackToBootInfClasses() {
        // given
        Function<String, InputStream> loader = loaderOf(Map.of(
                "BOOT-INF/classes/META-INF/build-info.properties", "build.version=3.0.0\n"));

        // when/then
        assertThat(new BuildInfoReader(loader).readVersion()).contains("3.0.0");
    }

    @Test
    void emptyWhenFileAbsent() {
        // given/when/then
        assertThat(new BuildInfoReader(loaderOf(Map.of())).readVersion()).isEqualTo(Optional.empty());
    }

    @Test
    void emptyWhenKeyAbsent() {
        // given
        Function<String, InputStream> loader = loaderOf(Map.of(
                "META-INF/build-info.properties", "build.name=demo\n"));

        // when/then
        assertThat(new BuildInfoReader(loader).readVersion()).isEqualTo(Optional.empty());
    }

    @Test
    void emptyOnReadErrorAndEachUnreadablePathIsLogged() {
        // given
        Function<String, InputStream> broken = path -> new InputStream() {
            @Override
            public int read() {
                throw new IllegalStateException("boom");
            }
        };

        // when
        Optional<String> version = new BuildInfoReader(broken).readVersion();

        // then
        assertThat(version).isEqualTo(Optional.empty());
        List<CapturedLogEvent> events = logs.events().stream()
                .filter(event -> "BUILD_INFO_UNREADABLE".equals(event.eventName()))
                .toList();
        assertThat(events)
                .extracting(event -> event.fields().get("resource"))
                .containsExactly(BuildInfoReader.BUILD_INFO_PATH, BuildInfoReader.BUILD_INFO_PATH_BOOT_INF);
        assertThat(events)
                .extracting(CapturedLogEvent::errorType)
                .containsOnly(IllegalStateException.class.getName());
    }

    @Test
    void linkageErrorIsPropagated() {
        // given
        LinkageError failure = new LinkageError("build-info dependency is unavailable");
        BuildInfoReader reader = new BuildInfoReader(path -> {
            throw failure;
        });

        // when
        // then
        assertThatThrownBy(reader::readVersion).isSameAs(failure);
    }
}
