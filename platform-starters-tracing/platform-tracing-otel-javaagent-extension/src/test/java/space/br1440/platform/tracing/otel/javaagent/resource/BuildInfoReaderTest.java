package space.br1440.platform.tracing.otel.javaagent.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class BuildInfoReaderTest {

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
                "META-INF/build-info.properties", "build.version=2.17.4\nbuild.name=demo\n"));

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
    void emptyOnReadError() {
        // given
        Function<String, InputStream> broken = path -> new InputStream() {
            @Override
            public int read() {
                throw new RuntimeException("boom");
            }
        };

        // when/then
        assertThat(new BuildInfoReader(broken).readVersion()).isEqualTo(Optional.empty());
    }
}
