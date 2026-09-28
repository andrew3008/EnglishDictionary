package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

final class SpanDataBoundaryScrubber {

    private static final RateLimitedLogger log = new RateLimitedLogger(SpanDataBoundaryScrubber.class);

    private final ScrubbingRuntime runtime;

    SpanDataBoundaryScrubber(ScrubbingRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    SpanBoundaryOutcome scrub(SpanData spanData) {
        runtime.recordBoundaryInputSpan();

        SpanBoundaryOutcome outcome;
        try {
            outcome = doScrub(spanData);
        } catch (Throwable t) {
            runtime.recordBoundaryCollapseFailure();
            PlatformThrowables.propagateIfFatal(t);
            log.log(ScrubbingLogEvent.SCRUB_BOUNDARY_FAILED, fields -> fields.cause(t));
            outcome = collapseFailClosed(spanData);
        }

        if (outcome.dropSpan()) {
            runtime.recordBoundarySpanDroppedFailClosed();
        } else {
            runtime.recordBoundaryOutputSpan();
            if (outcome.changed()) {
                runtime.recordBoundarySpanChanged();
            }
        }

        return outcome;
    }

    private SpanBoundaryOutcome doScrub(SpanData spanData) {
        Attributes originalAttributes = spanData.getAttributes();
        Attributes scrubbedAttributes;
        boolean attributesChanged;
        if (runtime.attributesVerified()) {
            AttributesScrubber.Result attributesResult =
                    AttributesScrubber.removeValueAttributeTrees(originalAttributes, runtime);
            scrubbedAttributes = attributesResult.attributes();
            attributesChanged = attributesResult.changed();
        } else {
            scrubbedAttributes = AttributesScrubber.collapseToSentinel(originalAttributes);
            attributesChanged = true;
            runtime.recordCollapsedSpan();
        }

        String originalName = spanData.getName();
        BoundaryTextPolicy.TextResult nameResult = BoundaryTextPolicy.scrubSpanName(originalName, runtime);
        boolean nameChanged = !Objects.equals(originalName, nameResult.value());

        ListOutcome<EventData> events = scrubEvents(spanData.getEvents());
        ListOutcome<LinkData> links = scrubLinks(spanData.getLinks());

        StatusData originalStatus = spanData.getStatus();
        BoundaryTextPolicy.TextResult descriptionResult =
                BoundaryTextPolicy.scrubStatusDescription(originalStatus.getDescription(), runtime);
        boolean statusChanged = !Objects.equals(originalStatus.getDescription(), descriptionResult.value());

        boolean criticalFailure = nameResult.criticalFailure()
                || descriptionResult.criticalFailure()
                || events.criticalFailure()
                || links.criticalFailure();
        if (criticalFailure) {
            log.log(ScrubbingLogEvent.CRITICAL_SCRUBBING_RULE_FAILED_AT_EXPORT_BOUNDARY);
            return SpanBoundaryOutcome.changed(collapseBoundarySurfaces(spanData));
        }

        if (nameChanged) {
            runtime.recordBoundarySpanNameScrubbed();
        }

        StatusData scrubbedStatus = statusChanged
                ? StatusData.create(originalStatus.getStatusCode(), descriptionResult.value())
                : originalStatus;

        boolean anyChanged = attributesChanged || nameChanged || events.changed() || links.changed() || statusChanged;
        if (!anyChanged) {
            return SpanBoundaryOutcome.unchanged(spanData);
        }

        SpanData result = new ScrubbedSpanData(
                spanData, nameResult.value(), scrubbedAttributes, spanData.getTotalAttributeCount(),
                events.values(), links.values(), scrubbedStatus
        );
        return SpanBoundaryOutcome.changed(result);
    }

    private SpanBoundaryOutcome collapseFailClosed(SpanData spanData) {
        try {
            return SpanBoundaryOutcome.changed(collapseBoundarySurfaces(spanData));
        } catch (Throwable t) {
            PlatformThrowables.propagateIfFatal(t);
            log.log(ScrubbingLogEvent.SCRUB_BOUNDARY_COLLAPSE_FAILED_DROPPING_SPAN,
                    fields -> fields.cause(t));
            return SpanBoundaryOutcome.drop();
        }
    }

    private SpanData collapseBoundarySurfaces(SpanData spanData) {
        Attributes attributes = AttributesScrubber.collapseToSentinel(spanData.getAttributes());

        List<EventData> events = collapseEvents(spanData.getEvents());
        List<LinkData> links = collapseLinks(spanData.getLinks());

        StatusData originalStatus = spanData.getStatus();
        String description = originalStatus.getDescription();
        StatusData status = StringUtils.isNullOrEmpty(description)
                ? originalStatus
                : StatusData.create(originalStatus.getStatusCode(), BoundaryTextPolicy.REDACTED);

        SpanData collapsed = new ScrubbedSpanData(
                spanData, BoundaryTextPolicy.REDACTED, attributes, spanData.getTotalAttributeCount(),
                events, links, status
        );

        runtime.recordBoundarySurfaceCollapsedFailClosed();
        return collapsed;
    }

    private List<EventData> collapseEvents(List<EventData> events) {
        if (events.isEmpty()) {
            return events;
        }

        List<EventData> collapsed = new ArrayList<>(events.size());
        for (EventData event : events) {
            if (event instanceof ExceptionEventData) {
                runtime.recordBoundaryExceptionEventCanonicalized();
            }

            runtime.recordBoundaryEventScrubbed();
            collapsed.add(EventData.create(
                    event.getEpochNanos(),
                    BoundaryTextPolicy.REDACTED,
                    AttributesScrubber.collapseToSentinel(event.getAttributes()),
                    event.getTotalAttributeCount()
            ));
        }

        return collapsed;
    }

    private List<LinkData> collapseLinks(List<LinkData> links) {
        if (links.isEmpty()) {
            return links;
        }

        List<LinkData> collapsed = new ArrayList<>(links.size());
        for (LinkData link : links) {
            runtime.recordBoundaryLinkScrubbed();
            collapsed.add(LinkData.create(
                    link.getSpanContext(),
                    AttributesScrubber.collapseToSentinel(link.getAttributes()),
                    link.getTotalAttributeCount()
            ));
        }

        return collapsed;
    }

    private ListOutcome<EventData> scrubEvents(List<EventData> events) {
        if (events.isEmpty()) {
            return new ListOutcome<>(events, false, false);
        }

        List<EventData> result = null;
        boolean criticalFailure = false;
        for (int i = 0; i < events.size(); i++) {
            EventData event = events.get(i);
            EventOutcome scrubbed = scrubEvent(event);
            criticalFailure |= scrubbed.criticalFailure();

            if (scrubbed.event() != event) {
                if (result == null) {
                    result = new ArrayList<>(events.size());
                    result.addAll(events.subList(0, i));
                }

                runtime.recordBoundaryEventScrubbed();
                result.add(scrubbed.event());
            } else if (result != null) {
                result.add(event);
            }
        }

        return (result == null)
                ? new ListOutcome<>(events, false, criticalFailure)
                : new ListOutcome<>(result, true, criticalFailure);
    }

    private EventOutcome scrubEvent(EventData event) {
        boolean isException = (event instanceof ExceptionEventData);

        AttributesScrubber.Result attrResult = AttributesScrubber.scrub(event.getAttributes(), runtime);
        BoundaryTextPolicy.TextResult nameResult = BoundaryTextPolicy.scrubEventName(event.getName(), runtime);
        boolean nameChanged = !Objects.equals(event.getName(), nameResult.value());
        boolean criticalFailure = attrResult.criticalFailure() || nameResult.criticalFailure();

        if (!isException && !attrResult.changed() && !nameChanged) {
            return new EventOutcome(event, criticalFailure);
        }

        if (isException) {
            runtime.recordBoundaryExceptionEventCanonicalized();
        }

        EventData canonical = EventData.create(
                event.getEpochNanos(), nameResult.value(), attrResult.attributes(), event.getTotalAttributeCount());
        return new EventOutcome(canonical, criticalFailure);
    }

    private ListOutcome<LinkData> scrubLinks(List<LinkData> links) {
        if (links.isEmpty()) {
            return new ListOutcome<>(links, false, false);
        }

        List<LinkData> result = null;
        boolean criticalFailure = false;
        for (int i = 0; i < links.size(); i++) {
            LinkData link = links.get(i);
            AttributesScrubber.Result scrubbed = AttributesScrubber.scrub(link.getAttributes(), runtime);
            criticalFailure |= scrubbed.criticalFailure();

            if (scrubbed.changed()) {
                if (result == null) {
                    result = new ArrayList<>(links.size());
                    result.addAll(links.subList(0, i));
                }

                runtime.recordBoundaryLinkScrubbed();
                result.add(LinkData.create(link.getSpanContext(), scrubbed.attributes(), link.getTotalAttributeCount()));
            } else if (result != null) {
                result.add(link);
            }
        }

        return (result == null)
                ? new ListOutcome<>(links, false, criticalFailure)
                : new ListOutcome<>(result, true, criticalFailure);
    }

    private record ListOutcome<T>(List<T> values, boolean changed, boolean criticalFailure) {
    }

    private record EventOutcome(EventData event, boolean criticalFailure) {
    }
}
