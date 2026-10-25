package space.br1440.platform.tracing.otel.javaagent.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtensionVersionResolverTest {

    private static final String EXPECTED_VERSION_PROPERTY = "platformTracing.expectedExtensionVersion";

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesGeneratedMetadataBesideTheExtensionClass() {
        String expectedVersion = System.getProperty(EXPECTED_VERSION_PROPERTY);

        assertThat(expectedVersion).isNotBlank();
        assertThat(ExtensionVersionResolver.resolve(PlatformExtensionReadiness.class))
                .contains(expectedVersion);
    }

    @Test
    void resolvesValidatedAndTrimmedVersion() {
        Properties metadata = validMetadata(" 3.4.5 ");

        assertThat(ExtensionVersionResolver.resolve(metadata)).contains("3.4.5");
    }

    @Test
    void unsupportedOrMissingFormatHasNoVersion() {
        for (String format : new String[]{null, "", "0", "2"}) {
            Properties metadata = validMetadata("3.4.5");
            putOrRemove(metadata, "format.version", format);

            assertThat(ExtensionVersionResolver.resolve(metadata))
                    .isEmpty();
        }
    }

    @Test
    void unexpectedOrMissingArtifactHasNoVersion() {
        for (String artifact : new String[]{null, "", "another-artifact"}) {
            Properties metadata = validMetadata("3.4.5");
            putOrRemove(metadata, "artifact", artifact);

            assertThat(ExtensionVersionResolver.resolve(metadata))
                    .isEmpty();
        }
    }

    @Test
    void missingOrBlankVersionHasNoVersion() {
        for (String version : new String[]{null, "", " ", "\t", "\r\n"}) {
            Properties metadata = validMetadata("3.4.5");
            putOrRemove(metadata, "version", version);

            assertThat(ExtensionVersionResolver.resolve(metadata))
                    .isEmpty();
        }
    }

    @Test
    void missingResourceHasNoVersion() throws IOException {
        URL missing = temporaryDirectory.resolve("missing.properties").toUri().toURL();

        assertThat(ExtensionVersionResolver.resolve(missing))
                .isEmpty();
    }

    @Test
    void malformedPropertiesHaveNoVersion() throws IOException {
        Path malformed = temporaryDirectory.resolve("malformed.properties");
        Files.writeString(malformed, "version=\\uZZZZ", StandardCharsets.ISO_8859_1);

        assertThat(ExtensionVersionResolver.resolve(malformed.toUri().toURL()))
                .isEmpty();
    }

    private static Properties validMetadata(String version) {
        Properties metadata = new Properties();
        metadata.setProperty("format.version", "1");
        metadata.setProperty("artifact", ExtensionVersionResolver.EXPECTED_ARTIFACT);
        metadata.setProperty("version", version);
        return metadata;
    }

    private static void putOrRemove(Properties properties, String key, String value) {
        if (value == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
    }
}
