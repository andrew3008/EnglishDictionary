package space.br1440.platform.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.util.CollectionUtils;
import space.br1440.platform.metrics.configuration.properties.MetricLabelRulesProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class MetricLabelRulesApplier {

    private final MetricLabelRulesProperties properties;

    public Tags apply(String metricName, Tags sourceTags) {
        MetricLabelRulesProperties.MetricRule rule = properties.getMetrics().get(metricName);
        if (rule == null) {
            return sourceTags;
        }

        Set<String> disabledTags = rule.getDisabledTags();
        if (CollectionUtils.isEmpty(disabledTags)) {
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