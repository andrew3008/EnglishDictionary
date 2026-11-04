package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import lombok.experimental.UtilityClass;

@UtilityClass
final class BatchIds {

    static final String NONE = "";

    static boolean isNone(String batchId) {
        return NONE.equals(batchId);
    }
}
