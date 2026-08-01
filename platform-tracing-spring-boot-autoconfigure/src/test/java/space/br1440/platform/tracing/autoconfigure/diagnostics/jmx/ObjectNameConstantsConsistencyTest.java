package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.management.openmbean.OpenType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlOpenTypeCodec;
import space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingObjectNames;

/**
 * Verifies that {@link PlatformTracingJmxObjectNames} (autoconfigure / App CL side) and
 * {@link PlatformTracingObjectNames} (otel-extension / Agent CL side) declare identical
 * ObjectName strings for every name duplicated on the Spring side.
 * <p>
 * This test catches drift between the two constant sets that would break JMX routing
 * at runtime (they cannot share a class due to classloader isolation but must be identical).
 */
class ObjectNameConstantsConsistencyTest {

    @Test
    void springDuplicatesExactlyMatchCanonicalAgentNames() throws Exception {
        Map<String, String> canonical = stringConstants(PlatformTracingObjectNames.class);
        Map<String, String> duplicated = stringConstants(PlatformTracingJmxObjectNames.class);
        Set<String> missing = new java.util.TreeSet<>(canonical.keySet());
        missing.removeAll(duplicated.keySet());
        Set<String> extra = new java.util.TreeSet<>(duplicated.keySet());
        extra.removeAll(canonical.keySet());
        Map<String, String> expectedDuplicated = new LinkedHashMap<>(canonical);
        missing.forEach(expectedDuplicated::remove);

        assertThat(duplicated)
                .as("Spring ObjectName duplicates; missing and extra keys are shown in the map diff")
                .isEqualTo(expectedDuplicated);
        assertThat(missing)
                .as("Намеренно не продублированные Agent ObjectName")
                .containsExactly("CONTROL_PROTOCOL_OBJECT_NAME_STR");
        assertThat(extra)
                .as("Spring не должен объявлять неизвестные Agent стороне ObjectName")
                .isEmpty();
    }

    @Test
    void springOpenTypesExactlyMatchCanonicalAgentSchema() throws Exception {
        Class<?> agentTypes = Class.forName(
                "space.br1440.platform.tracing.otel.javaagent.control.state.PrivateControlWireTypes");

        assertThat(openType(PrivateControlOpenTypeCodec.class, "ROUTE_ROW"))
                .isEqualTo(openType(agentTypes, "ROUTE_ROW"));
        assertThat(openType(PrivateControlOpenTypeCodec.class, "ROUTES"))
                .isEqualTo(openType(agentTypes, "ROUTES"));
        assertThat(openType(PrivateControlOpenTypeCodec.class, "POLICY"))
                .isEqualTo(openType(agentTypes, "POLICY"));
        assertThat(openType(PrivateControlOpenTypeCodec.class, "REQUEST"))
                .isEqualTo(openType(agentTypes, "REQUEST"));
        assertThat(openType(PrivateControlOpenTypeCodec.class, "RESPONSE"))
                .isEqualTo(openType(agentTypes, "RESPONSE"));
        assertThat(openType(PrivateControlOpenTypeCodec.class, "COMPLETE_RESPONSE"))
                .isEqualTo(openType(agentTypes, "ACCEPTED_COMPLETE"));
    }

    private static OpenType<?> openType(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (OpenType<?>) field.get(null);
    }

    private static Map<String, String> stringConstants(Class<?> type) throws IllegalAccessException {
        Map<String, String> constants = new TreeMap<>();
        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class
                    && field.getName().endsWith("_OBJECT_NAME_STR")) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        return constants;
    }
}
