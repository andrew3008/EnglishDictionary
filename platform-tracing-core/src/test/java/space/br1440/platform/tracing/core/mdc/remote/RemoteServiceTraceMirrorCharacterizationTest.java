package space.br1440.platform.tracing.core.mdc.remote;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.test.characterization.KnownDefect;
import space.br1440.platform.tracing.test.characterization.KnownDefectId;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteServiceTraceMirrorCharacterizationTest {

    private static final int CHARACTERIZED_CARDINALITY = 4_096;

    @Test
    @KnownDefect(KnownDefectId.UNBOUNDED_REMOTE_SERVICE_MIRROR)
    void mirrorRetainsEveryEntryWithoutBoundOrEviction() throws Exception {
        List<String> traceIds = new ArrayList<>(CHARACTERIZED_CARDINALITY);
        try {
            for (int index = 0; index < CHARACTERIZED_CARDINALITY; index++) {
                String traceId = "characterization-trace-" + index;
                traceIds.add(traceId);
                RemoteServiceTraceMirror.put(traceId, "remote-" + index);
            }

            assertThat(entries()).hasSize(CHARACTERIZED_CARDINALITY);
            assertThat(RemoteServiceTraceMirror.get(traceIds.getFirst())).contains("remote-0");
        } finally {
            traceIds.forEach(RemoteServiceTraceMirror::clear);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> entries() throws Exception {
        Field field = RemoteServiceTraceMirror.class.getDeclaredField("BY_TRACE");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }
}
