package space.br1440.platform.errorhandling.autoconfigure.observation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;

/**
 * Регистрирует noop-реализацию {@link ExceptionMetricRecorder} как fallback,
 * если {@link ErrorHandlingMetricsConfiguration} не создала бин.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingBean(ExceptionMetricRecorder.class)
public class ErrorHandlingNoopMetricsConfiguration {

    @Bean
    public ExceptionMetricRecorder noopExceptionMetricRecorder() {
        return new NoopExceptionMetricRecorder();
    }

}
