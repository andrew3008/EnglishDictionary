package space.br1440.platform.tracing.otel.javaagent.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class HandwrittenUpstreamAcpNamesForbiddenTest {

    private static final List<String> PINNED_UPSTREAM_NAMES = List.of(
            "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer",
            "io.opentelemetry.javaagent.shaded.instrumentation.resources.internal."
                    + "ResourceProviderPropertiesCustomizer",
            "io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer",
            "io.opentelemetry.javaagent.tooling.config.ConfigurationPropertiesSupplier",
            "io.opentelemetry.javaagent.tooling.config.OtlpProtocolPropertiesSupplier"
    );

    @Test
    void handwritten_main_sources_do_not_embed_pinned_upstream_names() throws IOException {
        // given
        Path mainJava = Path.of(System.getProperty("user.dir"), "src/main/java");
        assertThat(mainJava).isDirectory();

        // when
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainJava)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectHits(path, hits));
        }

        // then
        assertThat(hits).isEmpty();
    }

    private static void collectHits(Path path, List<String> hits) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
        for (String name : PINNED_UPSTREAM_NAMES) {
            if (source.contains(name)) {
                hits.add(path + ": " + name);
            }
        }
    }
}
