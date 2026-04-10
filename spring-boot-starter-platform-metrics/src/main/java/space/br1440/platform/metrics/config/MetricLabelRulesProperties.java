package space.br1440.platform.metrics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "platform-metric.labels")
public class MetricLabelRulesProperties {

    private Map<String, MetricRule> metrics = new HashMap<>();

    public Map<String, MetricRule> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, MetricRule> metrics) {
        this.metrics = metrics;
    }

    public static class MetricRule {
        private Set<String> disabledTags;

        public Set<String> getDisabledTags() {
            return disabledTags;
        }

        public void setDisabledTags(Set<String> disabledTags) {
            this.disabledTags = disabledTags;
        }
    }
}