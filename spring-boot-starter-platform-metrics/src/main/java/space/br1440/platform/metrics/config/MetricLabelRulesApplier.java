package space.br1440.platform.metrics.config;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MetricLabelRulesApplier {

    private final MetricLabelRulesProperties properties;

    public MetricLabelRulesApplier(MetricLabelRulesProperties properties) {
        this.properties = properties;
    }

    public Tags apply(String metricName, Tags sourceTags) {
        MetricLabelRulesProperties.MetricRule rule = properties.getMetrics().get(metricName);
        if (rule == null) {
            return sourceTags;
        }

        Set<String> disabledTags = rule.getDisabledTags();
        if (disabledTags == null || disabledTags.isEmpty()) {
            return sourceTags;
        }

        List<Tag> result = new ArrayList<>();

        for (Tag tag : sourceTags) {
            if (!disabledTags.contains(tag.getKey())) {
                result.add(tag);
            }
        }

        return Tags.of(result);
    }
}