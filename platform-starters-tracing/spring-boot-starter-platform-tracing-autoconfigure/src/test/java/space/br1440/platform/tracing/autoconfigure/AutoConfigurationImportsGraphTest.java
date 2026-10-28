package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;
import space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaProducerFactoryCustomizer;

class AutoConfigurationImportsGraphTest {

    private static final Path MODULE_IMPORTS = Path.of(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    );

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(moduleImports()));

    @Test
    void moduleImportLinesAreBootCandidates() {
        // given
        List<String> lines = moduleImportLines();
        List<String> candidates = ImportCandidates.load(
                AutoConfiguration.class,
                AutoConfigurationImportsGraphTest.class.getClassLoader()
        ).getCandidates();

        // then
        assertThat(lines).isNotEmpty();
        assertThat(candidates).containsAll(lines);
        for (String line : lines) {
            assertThat(ClassUtils.resolveClassName(line, AutoConfigurationImportsGraphTest.class.getClassLoader()))
                    .isNotNull();
        }
    }

    @Test
    void disabledConfigurationStartsWithoutPlatformOutboundPropagation() {
        // when
        runner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TraceOperations.class);
                    assertThat(context).doesNotHaveBean(PlatformOutboundPropagation.class);
                    assertThat(context.getBeansOfType(AcceptedTracingSettingsReader.class)).isEmpty();
                    assertThat(context.getBeansOfType(TracingSettingsWriter.class)).isEmpty();
                });
    }

    @Test
    void enabledWithoutAgentFailsClosed() {
        // when
        staticChannel()
                .withPropertyValues("platform.tracing.enabled=true")
                .run(context -> {
                    // then
                    assertFailureContains(context, "CONTROLLED_AGENT_MISSING");
                });
    }

    @Test
    void missingControlChannelFailsClosed() {
        // when
        runner.run(context -> {
            // then
            assertFailureContains(context, "Missing required property 'platform.tracing.control.channel'");
        });
    }

    @Test
    void userPropagationRemainsWithoutKafkaCustomizerWhenTracingDisabled() {
        // given
        PlatformOutboundPropagation user = (decision, control) -> OutboundPropagationHeaders.EMPTY;

        // when
        disabled()
                .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=true")
                .withBean(PlatformOutboundPropagation.class, () -> user)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PlatformOutboundPropagation.class);
                    assertThat(context.getBean(PlatformOutboundPropagation.class)).isSameAs(user);
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void customizerAbsentWithoutUserPropagationWhenHeadersEnabled() {
        // when
        disabled()
                .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=true")
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformOutboundPropagation.class);
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void customizerAbsentWhenPlatformHeadersAreNotPropagated() {
        // given
        PlatformOutboundPropagation user = (decision, control) -> OutboundPropagationHeaders.EMPTY;

        // when
        disabled()
                .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=false")
                .withBean(PlatformOutboundPropagation.class, () -> user)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PlatformOutboundPropagation.class);
                    assertThat(context.getBean(PlatformOutboundPropagation.class)).isSameAs(user);
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void kafkaClassesHiddenOmitsProducerCustomizer() {
        // given
        PlatformOutboundPropagation user = (decision, control) -> OutboundPropagationHeaders.EMPTY;

        // when
        disabled()
                .withClassLoader(new FilteredClassLoader(
                        "org.springframework.kafka",
                        "org.apache.kafka.clients"))
                .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=true")
                .withBean(PlatformOutboundPropagation.class, () -> user)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    private ApplicationContextRunner staticChannel() {
        return runner.withPropertyValues("platform.tracing.control.channel=NONE");
    }

    private ApplicationContextRunner disabled() {
        return staticChannel().withPropertyValues("platform.tracing.enabled=false");
    }

    private static Class<?>[] moduleImports() {
        return moduleImportLines().stream()
                .map(line -> ClassUtils.resolveClassName(line, AutoConfigurationImportsGraphTest.class.getClassLoader()))
                .toArray(Class<?>[]::new);
    }

    private static List<String> moduleImportLines() {
        try {
            return Files.readAllLines(MODULE_IMPORTS).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void assertFailureContains(AssertableApplicationContext context, String fragment) {
        assertThat(context).hasFailed();

        Throwable failure = context.getStartupFailure();
        assertThat(failure).isNotNull();

        String causeMessage = failure.getCause() == null ? "" : String.valueOf(failure.getCause().getMessage());
        assertThat(failure.getMessage() + " / " + causeMessage).contains(fragment);
    }
}
