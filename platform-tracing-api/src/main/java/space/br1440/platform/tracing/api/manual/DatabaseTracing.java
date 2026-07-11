package space.br1440.platform.tracing.api.manual;

import space.br1440.platform.tracing.api.semconv.DatabaseSemconvVersion;

@DatabaseSemconvVersion("1.28.0")
public interface DatabaseTracing extends DatabaseSpanBuilder {
}
