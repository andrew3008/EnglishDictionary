package space.br1440.platform.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MapUtilsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("isNullOrEmptyCases")
    void isNullOrEmpty_returnsExpectedResult(String caseName, boolean expected, Map<String, String> map) {
        // when
        boolean actual = MapUtils.isNullOrEmpty(map);

        // then
        assertThat(actual).as(caseName).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("containsKeyCases")
    void containsKey_returnsExpectedResult(String caseName,
                                           boolean expected,
                                           Map<String, String> map,
                                           String key) {
        // when
        boolean actual = MapUtils.containsKey(map, key);

        // then
        assertThat(actual).as(caseName).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("getOrDefaultCases")
    void getOrDefault_returnsExpectedResult(String caseName,
                                            String expected,
                                            Map<String, String> map,
                                            String key,
                                            String defaultValue) {
        // when
        String actual = MapUtils.getOrDefault(map, key, defaultValue);

        // then
        assertThat(actual).as(caseName).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("sizeCases")
    void size_returnsExpectedResult(String caseName, int expected, Map<String, String> map) {
        // when
        int actual = MapUtils.size(map);

        // then
        assertThat(actual).as(caseName).isEqualTo(expected);
    }

    static Stream<Arguments> isNullOrEmptyCases() {
        return Stream.of(
                arguments("null", true, null),
                arguments("empty", true, Map.of()),
                arguments("singleton", false, Map.of("k", "v")),
                arguments("null value", false, mapWithNullValue())
        );
    }

    static Stream<Arguments> containsKeyCases() {
        return Stream.of(
                arguments("null map", false, null, "k"),
                arguments("empty", false, Map.of(), "k"),
                arguments("missing key", false, Map.of("a", "b"), "k"),
                arguments("present key", true, Map.of("k", "v"), "k"),
                arguments("null key absent", false, mapWithValue("k", "v"), null),
                arguments("null key present", true, mapWithNullKey(), null)
        );
    }

    static Stream<Arguments> getOrDefaultCases() {
        return Stream.of(
                arguments("null map", "d", null, "k", "d"),
                arguments("empty map", "d", Map.of(), "k", "d"),
                arguments("missing key", "d", Map.of("a", "b"), "k", "d"),
                arguments("present key", "v", Map.of("k", "v"), "k", "d"),
                arguments("null stored value", null, mapWithNullValue(), "k", "d"),
                arguments("null default on miss", null, Map.of(), "k", null)
        );
    }

    static Stream<Arguments> sizeCases() {
        return Stream.of(
                arguments("null", 0, null),
                arguments("empty", 0, Map.of()),
                arguments("singleton", 1, Map.of("k", "v"))
        );
    }

    @SuppressWarnings("SameParameterValue")
    private static Map<String, String> mapWithValue(String key, String value) {
        Map<String, String> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, String> mapWithNullValue() {
        Map<String, String> map = new HashMap<>();
        map.put("k", null);
        return map;
    }

    private static Map<String, String> mapWithNullKey() {
        Map<String, String> map = new HashMap<>();
        map.put(null, "v");
        return map;
    }
}
