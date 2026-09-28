package space.br1440.platform.tracing.otel.javaagent.logging.event;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AgentLogEventCatalog {

    private static final List<AgentLogEventType> ALL = Stream.<AgentLogEventType[]>of(
                BootstrapLogEvent.values(),
                ResourceIdentityLogEvent.values(),
                ControlPlaneLogEvent.values(),
                ExporterLogEvent.values(),
                ExportPipelineLogEvent.values(),
                SpanTimeoutLogEvent.values(),
                ScrubbingLogEvent.values(),
                ValidationLogEvent.values(),
                SamplingLogEvent.values(),
                LogThrottlingLogEvent.values())
            .flatMap(Arrays::stream)
            .toList();

    public static List<AgentLogEventType> all() {
        return ALL;
    }
}
