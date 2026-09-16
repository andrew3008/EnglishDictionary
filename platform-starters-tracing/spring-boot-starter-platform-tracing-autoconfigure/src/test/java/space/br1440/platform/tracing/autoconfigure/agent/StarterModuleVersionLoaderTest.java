package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.errorhandling.startup.PlatformConfigurationException;

class StarterModuleVersionLoaderTest {

    @Test
    void starterModuleVersionMatchesTheVersionOfTheCurrentBuild() {
        // given
        String expectedModuleVersion = System.getProperty("platformTracing.expectedModuleVersion");

        // when
        String starterModuleVersion = StarterModuleVersionLoader.starterModuleVersion();

        // then
        assertThat(expectedModuleVersion).isNotBlank();
        assertThat(starterModuleVersion).isEqualTo(expectedModuleVersion);
    }

    @Test
    void repeatedReadsOfBrokenMetadataKeepThrowingPlatformConfigurationException() {
        // given
        URL absent = null;

        // when
        // then
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> StarterModuleVersionLoader.readModuleVersion(absent))
                    .isInstanceOf(PlatformConfigurationException.class)
                    .hasMessageContaining("Missing starter compatibility metadata resource");
        }
    }

    @Test
    void blankModuleVersionIsRejected(@TempDir Path directory) throws IOException {
        // given
        URL resource = metadata(directory, "module.version=\n");

        // when
        // then
        assertThatThrownBy(() -> StarterModuleVersionLoader.readModuleVersion(resource))
                .isInstanceOf(PlatformConfigurationException.class)
                .hasMessageContaining("Missing module.version in starter compatibility metadata");
    }

    @Test
    void malformedPropertiesContentIsRejectedWithTheOriginalCause(@TempDir Path directory) throws IOException {
        // given
        URL resource = metadata(directory, "module.version=\\u12XZ\n");

        // when
        // then
        assertThatThrownBy(() -> StarterModuleVersionLoader.readModuleVersion(resource))
                .isInstanceOf(PlatformConfigurationException.class)
                .hasMessageContaining("Cannot read starter compatibility metadata resource")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private static URL metadata(Path directory, String content) throws IOException {
        Path file = Files.createTempFile(directory, "module-version-", ".properties");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toUri().toURL();
    }
}
