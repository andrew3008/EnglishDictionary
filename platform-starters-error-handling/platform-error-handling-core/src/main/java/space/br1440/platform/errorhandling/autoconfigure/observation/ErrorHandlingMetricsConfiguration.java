package space.br1440.platform.errorhandling.autoconfigure.observation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.metrics.MicrometerExceptionMetricRecorder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
public class ErrorHandlingMetricsConfiguration {

    private static final int MAX_EXCEPTION_TYPE_TAGS = 128;

    @Bean
    @ConditionalOnMissingBean(ExceptionMetricRecorder.class)
    public ExceptionMetricRecorder micrometerExceptionMetricRecorder(MeterRegistry meterRegistry) {
        return new MicrometerExceptionMetricRecorder(meterRegistry);
    }

    @Bean
    public MeterFilter exceptionHandlingMeterFilter() {
        return MeterFilter.maximumAllowableTags(
                MicrometerExceptionMetricRecorder.METRIC_HANDLED,
                MicrometerExceptionMetricRecorder.TAG_EXCEPTION_TYPE,
                MAX_EXCEPTION_TYPE_TAGS,
                MeterFilter.deny()
        );
    }

    @Bean
    public MeterFilter unhandledExceptionMeterFilter() {
        return MeterFilter.maximumAllowableTags(
                MicrometerExceptionMetricRecorder.METRIC_UNHANDLED,
                MicrometerExceptionMetricRecorder.TAG_EXCEPTION_TYPE,
                MAX_EXCEPTION_TYPE_TAGS,
                MeterFilter.deny()
        );
    }
}
