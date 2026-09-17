package space.br1440.platform.tracing.autoconfigure.span;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.otel.span.enrich.DefaultSpanEnricher;

import static org.assertj.core.api.Assertions.assertThat;

class SpanEnrichmentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SpanEnrichmentAutoConfiguration.class));

    @Test
    void defaultEnricherIsUnique() {
        contextRunner.run(context -> {
            // given / when / then
            assertThat(context).hasSingleBean(SpanEnricher.class);
            assertThat(context.getBean(SpanEnricher.class)).isInstanceOf(DefaultSpanEnricher.class);
            assertThat(context).hasBean("platformSpanEnricher");
        });
    }

    /** Проверяется только back-off авто-конфигурации: вызов enrichment-пайплайна здесь не доказывается. */
    @Test
    void applicationDefinedSpanEnricherReplacesDefaultBean() {
        SpanEnricher custom = enrichment -> {
        };

        contextRunner
                .withBean(SpanEnricher.class, () -> custom)
                .run(context -> {
                    // given / when / then
                    assertThat(context).hasSingleBean(SpanEnricher.class);
                    assertThat(context.getBean(SpanEnricher.class)).isSameAs(custom);
                    assertThat(context.getBean(SpanEnricher.class)).isNotInstanceOf(DefaultSpanEnricher.class);
                });
    }
}
