package space.br1440.platform.tracing.autoconfigure.jmx;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingObjectNames;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

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
                .isEmpty();
        assertThat(extra)
                .as("Spring не должен объявлять неизвестные Agent стороне ObjectName")
                .isEmpty();
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
