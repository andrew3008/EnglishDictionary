package space.br1440.platform.tracing.otel.runtime;

public record TraceControlBoundaryState(boolean forceTrace, boolean qaTrace) {

    public static final TraceControlBoundaryState EMPTY = new TraceControlBoundaryState(false, false);

}
