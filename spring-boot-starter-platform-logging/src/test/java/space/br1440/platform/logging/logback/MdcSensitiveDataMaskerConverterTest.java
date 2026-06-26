package space.br1440.platform.logging.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import space.br1440.platform.logging.configuration.LoggingProperties;
import space.br1440.platform.logging.masking.SensitiveDataMasker;
import space.br1440.platform.logging.mdc.TracingMdcLogKeys;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Проверяет, что ключи трассировки platform-tracing v1 ({@code traceId}/{@code spanId}/{@code traceFlags})
 * попадают в текстовый MDC-блок {@code %maskedMDC}, а не отфильтровываются по умолчанию.
 */
@ExtendWith(MockitoExtension.class)
class MdcSensitiveDataMaskerConverterTest {

    @Mock
    private SensitiveDataMasker masker;

    @Mock
    private ILoggingEvent event;

    private MdcSensitiveDataMaskerConverter converter;

    @BeforeEach
    void setUp() {
        converter = new MdcSensitiveDataMaskerConverter();
        LoggingProperties properties = new LoggingProperties();
        when(masker.maskValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        MdcSensitiveDataMaskerConverter.setMasker(masker, properties);
    }

    @Test
    void includesTracingMdcKeysInOutput() {
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put(TracingMdcLogKeys.TRACE_ID, "abc123def4567890abc123def4567890");
        mdc.put(TracingMdcLogKeys.SPAN_ID, "abc123def4567890");
        mdc.put(TracingMdcLogKeys.TRACE_FLAGS, "01");
        mdc.put("correlation_id", "corr-should-be-excluded");
        when(event.getMDCPropertyMap()).thenReturn(mdc);

        String result = converter.convert(event);

        assertThat(result).contains("traceId=abc123def4567890abc123def4567890");
        assertThat(result).contains("spanId=abc123def4567890");
        assertThat(result).contains("traceFlags=01");
        assertThat(result).doesNotContain("correlation_id=");
    }

    @Test
    void respectsCustomExcludedKeys() {
        LoggingProperties properties = new LoggingProperties();
        properties.setMdcExcludedKeys(Set.of(TracingMdcLogKeys.TRACE_ID));
        MdcSensitiveDataMaskerConverter.setMasker(masker, properties);

        Map<String, String> mdc = Map.of(
                TracingMdcLogKeys.TRACE_ID, "hidden",
                TracingMdcLogKeys.SPAN_ID, "visible");
        when(event.getMDCPropertyMap()).thenReturn(mdc);

        String result = converter.convert(event);

        assertThat(result).doesNotContain("traceId=");
        assertThat(result).contains("spanId=visible");
    }
}
