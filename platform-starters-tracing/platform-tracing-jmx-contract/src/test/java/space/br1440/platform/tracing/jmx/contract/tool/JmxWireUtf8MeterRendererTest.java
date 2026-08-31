package space.br1440.platform.tracing.jmx.contract.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JmxWireUtf8MeterRendererTest {

    private static Method utf8Length;

    @BeforeAll
    static void compileGeneratedMeter() throws Exception {
        String packageName = "space.br1440.platform.tracing.jmx.contract.generated.utf8meter";
        Class<?> generated = GeneratedContractSourceCompiler.compileAndLoad(
                "jmx-wire-utf8-meter-",
                "renderUtf8Meter",
                new Class<?>[]{String.class},
                new Object[]{packageName},
                packageName,
                "JmxWireUtf8Meter");
        utf8Length = generated.getMethod("utf8Length", String.class);
    }

    @ParameterizedTest
    @CsvSource({
            "'', 0",
            "ascii, 5",
            "я, 2",
            "€, 3"
    })
    void wellFormedTextMatchesJdkUtf8ByteLength(String value, int expectedBytes) throws Exception {
        // when
        long measured = (long) utf8Length.invoke(null, value);

        // then
        assertThat(measured).isEqualTo(expectedBytes);
        assertThat(measured).isEqualTo(value.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void supplementaryPlaneCharacterIsFourBytes() throws Exception {
        // given
        String emoji = "\uD83D\uDE00";

        // when
        long measured = (long) utf8Length.invoke(null, emoji);

        // then
        assertThat(measured).isEqualTo(4);
        assertThat(measured).isEqualTo(emoji.getBytes(StandardCharsets.UTF_8).length);
    }

    @ParameterizedTest
    @CsvSource({
            "\uD83D, 3",
            "\uDE00, 3",
            "\uD83D\uD83D, 6"
    })
    void unpairedSurrogatesCountAsThreeBytes(String value, int expectedBytes) throws Exception {
        // when
        long measured = (long) utf8Length.invoke(null, value);

        // then
        assertThat(measured).isEqualTo(expectedBytes);
    }
}
