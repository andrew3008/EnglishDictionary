package space.br1440.platform.logging.configuration;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@Accessors (chain = true)
@ConditionalOnMissingBean
@ConfigurationProperties(prefix = LoggingProperties.PREFIX)
public class LoggingProperties {

    public static final String PREFIX = "platform.http-logging";

    /**
     * Ключи MDC, исключаемые из текстового блока {@code %maskedMDC} (шумные HTTP/служебные поля).
     * <p>
     * Ключи трассировки {@code traceId}/{@code spanId}/{@code traceFlags} (camelCase, platform-tracing v1)
     * <b>не</b> исключаются — они должны попадать в лог для корреляции с Jaeger/ELK.
     * Устаревший snake_case {@code trace_flags} удалён из списка: Micrometer bridge больше не пишет его.
     */
    private static final Set<String> DEFAULT_MDC_KEYS_TO_EXCLUDE = Set.of(
            "status_code", "start_time", "end_time", "duration",
            "correlation_id"
    );

    private Boolean enable = true;
    private Set<String> mdcExcludedKeys = DEFAULT_MDC_KEYS_TO_EXCLUDE;
    private String customSensitiveDataRulesFilePath;
    private ServletFilter servletFilter = new ServletFilter();
    private HttpLoggingSettings request = new HttpLoggingSettings();
    private HttpLoggingSettings response = new HttpLoggingSettings();

    @Getter @Setter @Accessors(chain = true)
    public static class ServletFilter {
        private Boolean enabled = true;
    }

    @Getter @Setter @AllArgsConstructor @NoArgsConstructor @Accessors (chain = true)
    public static class HttpLoggingSettings {
        private BodyProperties body = new BodyProperties();
        private HeaderProperties headers = new HeaderProperties();

        @Getter @Setter @AllArgsConstructor @NoArgsConstructor @Accessors (chain = true)
        public static class BodyProperties {
            private Boolean enable = true;
            private Integer maxLength = -1;
        }

        @Getter @Setter @AllArgsConstructor @NoArgsConstructor @Accessors (chain = true)
        public static class HeaderProperties {
            private Boolean enable = true;
            private List<String> excludeHeaders = new ArrayList<>();
        }
    }

}
