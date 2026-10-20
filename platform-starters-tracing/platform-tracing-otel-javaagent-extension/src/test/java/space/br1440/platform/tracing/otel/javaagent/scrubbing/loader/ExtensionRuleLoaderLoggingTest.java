package space.br1440.platform.tracing.otel.javaagent.scrubbing.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class ExtensionRuleLoaderLoggingTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void warning_uses_the_typed_failure_context_field(@TempDir Path directory) {
        // given
        ExtensionRuleLoader loader = new ExtensionRuleLoader(
                ExtensionRuleLoader.ValidationMode.WARNING
        );
        Path missingJar = directory.resolve("rules.jar");

        // when
        try (loader) {
            loader.load(missingJar.toString());
        }

        // then
        assertThat(logs.events())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.message()).isEqualTo("Extension scrubbing rule skipped");
                    assertThat(event.fields())
                            .containsEntry("name", "rules.jar")
                            .containsEntry("reason", "MISSING_PATH")
                            .containsEntry("failureContext", "missing-path")
                            .doesNotContainKey("detail");
                });
    }

    @Test
    void unreadable_jar_warning_preserves_the_inspection_cause(@TempDir Path directory) throws IOException {
        // given
        Path jar = directory.resolve("corrupt.jar");
        Files.writeString(jar, "not a jar");
        ExtensionRuleLoader loader = new ExtensionRuleLoader(
                ExtensionRuleLoader.ValidationMode.WARNING
        );

        // when
        try (loader) {
            loader.load(jar.toString());
        }

        // then
        assertThat(logs.events())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.fields())
                            .containsEntry("name", "corrupt.jar")
                            .containsEntry("reason", "UNREADABLE_FILE")
                            .containsEntry("failureContext", "jar-inspection");
                    assertThat(event.throwableDigest()).contains("java.util.zip.ZipException");
                });
    }
}
