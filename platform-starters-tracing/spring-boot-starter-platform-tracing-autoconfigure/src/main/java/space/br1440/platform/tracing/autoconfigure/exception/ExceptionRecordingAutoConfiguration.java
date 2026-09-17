package space.br1440.platform.tracing.autoconfigure.exception;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.exception.ExceptionRecordingPolicy;

import io.opentelemetry.api.OpenTelemetry;

@AutoConfiguration
@ConditionalOnClass(OpenTelemetry.class)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class ExceptionRecordingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExceptionRecorder platformExceptionRecorder(TracingProperties properties) {
        TracingProperties.ExceptionRecording recording = properties.getExceptionRecording();
        ExceptionRecordingPolicy recordingPolicy = new ExceptionRecordingPolicy(
                recording.isIncludeMessage(),
                recording.isIncludeStacktrace()
        );

        return new ExceptionRecorder(recordingPolicy);
    }
}
