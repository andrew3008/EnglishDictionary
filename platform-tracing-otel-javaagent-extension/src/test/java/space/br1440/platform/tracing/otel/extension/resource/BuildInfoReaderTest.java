package space.br1440.platform.tracing.otel.extension.resource;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoReaderTest {

    /** Загрузчик поверх карты path -> содержимое; отсутствующий путь -> null. */
    private static ClasspathResourceLoader loaderOf(Map<String, String> contentByPath) {
        return path -> {
            String content = contentByPath.get(path);
            return content == null ? null : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        };
    }

    @Test
    void читает_build_version_из_root() {
        ClasspathResourceLoader loader = loaderOf(Map.of(
                "META-INF/build-info.properties", "build.version=2.17.4\nbuild.name=demo\n"));

        assertThat(new BuildInfoReader(loader).readVersion()).contains("2.17.4");
    }

    @Test
    void fallback_на_BOOT_INF_classes() {
        ClasspathResourceLoader loader = loaderOf(Map.of(
                "BOOT-INF/classes/META-INF/build-info.properties", "build.version=3.0.0\n"));

        assertThat(new BuildInfoReader(loader).readVersion()).contains("3.0.0");
    }

    @Test
    void empty_когда_файл_отсутствует() {
        assertThat(new BuildInfoReader(loaderOf(Map.of())).readVersion()).isEqualTo(Optional.empty());
    }

    @Test
    void empty_когда_ключ_отсутствует() {
        ClasspathResourceLoader loader = loaderOf(Map.of(
                "META-INF/build-info.properties", "build.name=demo\n"));

        assertThat(new BuildInfoReader(loader).readVersion()).isEqualTo(Optional.empty());
    }

    @Test
    void empty_при_ошибке_чтения() {
        ClasspathResourceLoader broken = path -> new InputStream() {
            @Override
            public int read() {
                throw new RuntimeException("boom");
            }
        };

        assertThat(new BuildInfoReader(broken).readVersion()).isEqualTo(Optional.empty());
    }
}
