package space.br1440.platform.datasource.support.assertj;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.assertj.core.api.AbstractAssert;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Fluent AssertJ для Micrometer {@link MeterRegistry}: проверяет наличие counter/gauge,
 * значения и теги.
 */
public class MetricsAssert extends AbstractAssert<MetricsAssert, MeterRegistry> {

    public MetricsAssert(MeterRegistry registry) {
        super(registry, MetricsAssert.class);
    }

    public MetricsAssert hasCounter(String name, String tagKey, String tagValue) {
        isNotNull();
        Counter counter = actual.find(name).tag(tagKey, tagValue).counter();
        if (counter == null) {
            failWithMessage(
                    "Expected counter <%s> with tag <%s>=<%s> but it was not registered. Registered: %s",
                    name, tagKey, tagValue, listMeters(name));
        }
        return this;
    }

    public MetricsAssert hasCounterValue(String name, String tagKey, String tagValue, double expected) {
        hasCounter(name, tagKey, tagValue);
        Counter counter = actual.find(name).tag(tagKey, tagValue).counter();
        if (counter.count() != expected) {
            failWithMessage(
                    "Expected counter <%s>{%s=%s} value <%.4f> but was <%.4f>",
                    name, tagKey, tagValue, expected, counter.count());
        }
        return this;
    }

    public MetricsAssert hasCounterValueAtLeast(String name, String tagKey, String tagValue, double minimum) {
        hasCounter(name, tagKey, tagValue);
        Counter counter = actual.find(name).tag(tagKey, tagValue).counter();
        if (counter.count() < minimum) {
            failWithMessage(
                    "Expected counter <%s>{%s=%s} value >= <%.4f> but was <%.4f>",
                    name, tagKey, tagValue, minimum, counter.count());
        }
        return this;
    }

    public MetricsAssert hasGauge(String name, String tagKey, String tagValue) {
        isNotNull();
        Gauge gauge = actual.find(name).tag(tagKey, tagValue).gauge();
        if (gauge == null) {
            failWithMessage(
                    "Expected gauge <%s> with tag <%s>=<%s> but it was not registered. Registered: %s",
                    name, tagKey, tagValue, listMeters(name));
        }
        return this;
    }

    public MetricsAssert hasGaugeValue(String name, String tagKey, String tagValue, double expected) {
        hasGauge(name, tagKey, tagValue);
        Gauge gauge = actual.find(name).tag(tagKey, tagValue).gauge();
        if (gauge.value() != expected) {
            failWithMessage(
                    "Expected gauge <%s>{%s=%s} value <%.4f> but was <%.4f>",
                    name, tagKey, tagValue, expected, gauge.value());
        }
        return this;
    }

    public MetricsAssert hasNoMeter(String name, String tagKey, String tagValue) {
        isNotNull();
        Meter meter = actual.find(name).tag(tagKey, tagValue).meter();
        if (meter != null) {
            failWithMessage(
                    "Expected NO meter <%s>{%s=%s} but found one (type=%s)",
                    name, tagKey, tagValue, meter.getClass().getSimpleName());
        }
        return this;
    }

    private String listMeters(String name) {
        Tags noTags = Tags.empty();
        Collection<Meter> meters = actual.find(name).meters();
        if (meters.isEmpty()) {
            return "<none with that name>; total in registry: "
                    + actual.getMeters().stream()
                            .map(m -> m.getId().getName())
                            .distinct()
                            .collect(Collectors.joining(", "));
        }
        return meters.stream()
                .map(m -> m.getId().getName() + Tags.of(m.getId().getTagsAsIterable())
                        .stream()
                        .map(Tag::getKey)
                        .collect(Collectors.joining(",", "{", "}")))
                .collect(Collectors.joining("; "));
    }
}
