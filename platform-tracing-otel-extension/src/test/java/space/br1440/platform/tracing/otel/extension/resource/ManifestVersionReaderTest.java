package space.br1440.platform.tracing.otel.extension.resource;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestVersionReaderTest {

    private static ClasspathResourceLoader loaderOf(Map<String, String> contentByPath) {
        return path -> {
            String content = contentByPath.get(path);
            return content == null ? null : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        };
    }

    @Test
    void читает_implementation_version() {
        ClasspathResourceLoader loader = loaderOf(Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 1.0.0\r\n\r\n"));

        assertThat(new ManifestVersionReader(loader).readVersion()).contains("1.0.0");
    }

    @Test
    void empty_когда_manifest_отсутствует() {
        assertThat(new ManifestVersionReader(loaderOf(Map.of())).readVersion()).isEqualTo(Optional.empty());
    }

    @Test
    void empty_когда_ключ_отсутствует() {
        ClasspathResourceLoader loader = loaderOf(Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n"));

        assertThat(new ManifestVersionReader(loader).readVersion()).isEqualTo(Optional.empty());
    }
}
