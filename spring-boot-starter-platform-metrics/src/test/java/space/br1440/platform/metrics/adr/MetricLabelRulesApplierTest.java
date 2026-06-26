package space.br1440.platform.metrics.adr;

import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.metrics.MetricLabelRulesApplier;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.configuration.properties.MetricLabelRulesProperties;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ADR: MetricLabelRulesApplier")
class MetricLabelRulesApplierTest {

    @Test
    void removesDisabledTag() {
        MetricLabelRulesProperties properties = new MetricLabelRulesProperties();
        properties.getMetrics().put("platform.http.requests", new MetricLabelRulesProperties.MetricRule()
                .setDisabledTags(Set.of(MetricTags.STATUS)));
        MetricLabelRulesApplier applier = new MetricLabelRulesApplier(properties);
        Tags source = Tags.of(MetricTags.METHOD, "GET", MetricTags.PATH, "/api/users/{id}", MetricTags.STATUS, "200");
        Tags result = applier.apply("platform.http.requests", source);
        assertThat(result.stream().map(t -> t.getKey())).containsExactlyInAnyOrder(MetricTags.METHOD, MetricTags.PATH);
    }
}
