package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.util.Collections;
import java.util.List;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

final class ScrubbedSpanData extends DelegatingSpanData {

    private final String name;
    private final Attributes attributes;
    private final int totalAttributeCount;
    private final List<EventData> events;
    private final List<LinkData> links;
    private final StatusData status;

    ScrubbedSpanData(SpanData delegate,
                     String name,
                     Attributes attributes,
                     int totalAttributeCount,
                     List<EventData> events,
                     List<LinkData> links,
                     StatusData status) {
        super(delegate);
        this.name = name;
        this.attributes = attributes;
        this.totalAttributeCount = totalAttributeCount;
        this.events = Collections.unmodifiableList(events);
        this.links = Collections.unmodifiableList(links);
        this.status = status;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Attributes getAttributes() {
        return attributes;
    }

    @Override
    public int getTotalAttributeCount() {
        return totalAttributeCount;
    }

    @Override
    public List<EventData> getEvents() {
        return events;
    }

    @Override
    public List<LinkData> getLinks() {
        return links;
    }

    @Override
    public StatusData getStatus() {
        return status;
    }
}
