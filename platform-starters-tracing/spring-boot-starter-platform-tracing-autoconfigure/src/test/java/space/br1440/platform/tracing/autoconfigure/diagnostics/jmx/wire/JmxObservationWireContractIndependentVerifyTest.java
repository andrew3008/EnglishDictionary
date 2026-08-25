package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JmxObservationWireContractIndependentVerifyTest {

    @Test
    void goldenKeySetExactlyMatchesReflectedScalarConstants() throws Exception {
        Map<String, String> golden = loadGolden();
        Map<String, String> reflected = reflectScalarConstants();

        assertThat(reflected.keySet()).containsExactlyInAnyOrderElementsOf(golden.keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenEntries")
    void constantValueMatchesGoldenResource(String constantName, String expectedValue) throws Exception {
        Map<String, String> reflected = reflectScalarConstants();

        assertThat(reflected).containsEntry(constantName, expectedValue);
    }

    private static Stream<Arguments> goldenEntries() throws Exception {
        return loadGolden().entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static Map<String, String> reflectScalarConstants() {
        Map<String, String> constants = new LinkedHashMap<>();
        for (Field field : JmxObservationWireContract.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) || !Modifier.isPublic(modifiers)) {
                continue;
            }

            Class<?> type = field.getType();
            if (type != String.class && type != int.class) {
                continue;
            }

            try {
                Object value = field.get(null);
                constants.put(field.getName(), String.valueOf(value));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        return constants;
    }

    private static Map<String, String> loadGolden() throws Exception {
        String resource = "jmx-observation-wire-contract-golden.properties";
        InputStream stream = JmxObservationWireContractIndependentVerifyTest.class
                .getClassLoader()
                .getResourceAsStream(resource);
        assertThat(stream).as(resource).isNotNull();

        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                int eq = line.indexOf('=');
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }

        return values;
    }
}
